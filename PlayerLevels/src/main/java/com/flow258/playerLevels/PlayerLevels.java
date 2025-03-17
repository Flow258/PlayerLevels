package com.flow258.playerLevels;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Statistic;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class PlayerLevels extends JavaPlugin implements Listener {

    private Map<UUID, PlayerData> playerDataMap = new HashMap<>();
    private Connection connection;
    private String storageType;
    private double baseXp;
    private double xpMultiplier;
    private List<StatisticConfig> statisticConfigs;
    private boolean pluginEnabled;

    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        // Check if PlaceholderAPI is installed
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().warning("PlaceholderAPI not found! Placeholders will not work.");
        } else {
            // Register placeholders
            new PlayerLevelExpansion(this).register();
        }

        // Register commands
        getCommand("level").setExecutor(new LevelCommand(this));
        getCommand("level").setTabCompleter(new LevelTabCompleter());
        getCommand("leveltop").setExecutor(new LevelTopCommand(this));

        // Load configuration
        loadConfig();

        // Initialize database
        initializeDatabase();

        // Start periodic XP calculation task
        startXpCalculationTask();

        getLogger().info("PlayerLevels plugin enabled!");
    }

    @Override
    public void onDisable() {
        // Save all player data
        saveAllPlayerData();

        // Close database connection
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Error closing database connection", e);
            }
        }

        getLogger().info("PlayerLevels plugin disabled!");
    }

    public void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();

        // Load basic settings
        pluginEnabled = config.getBoolean("settings.enable-plugin", true);
        baseXp = config.getDouble("settings.levels.base-xp", 100);
        xpMultiplier = config.getDouble("settings.levels.xp-multiplier", 1.5);
        storageType = config.getString("storage.type", "sqlite").toLowerCase();

        // Load statistics configurations
        statisticConfigs = new ArrayList<>();
        ConfigurationSection statsSection = config.getConfigurationSection("settings.statistics");

        if (statsSection != null) {
            for (String key : statsSection.getKeys(false)) {
                ConfigurationSection statSection = statsSection.getConfigurationSection(key);
                if (statSection != null) {
                    String statisticName = statSection.getString("statistic");
                    String materialName = statSection.getString("material", "");
                    String entityName = statSection.getString("entity", "");
                    double xpValue = statSection.getDouble("xp-value");

                    try {
                        Statistic statistic = Statistic.valueOf(statisticName);
                        Material material = null;
                        EntityType entityType = null;

                        if (!materialName.isEmpty()) {
                            material = Material.valueOf(materialName);
                        }

                        if (!entityName.isEmpty()) {
                            entityType = EntityType.valueOf(entityName);
                        }

                        statisticConfigs.add(new StatisticConfig(statistic, material, entityType, xpValue));
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Invalid statistic, material, or entity in config: " + statisticName);
                    }
                }
            }
        }

        getLogger().info("Loaded " + statisticConfigs.size() + " statistics configurations");
    }

    private void initializeDatabase() {
        try {
            if ("mysql".equals(storageType)) {
                // MySQL setup
                String host = getConfig().getString("storage.mysql.host", "localhost");
                int port = getConfig().getInt("storage.mysql.port", 3306);
                String database = getConfig().getString("storage.mysql.database", "minecraft");
                String username = getConfig().getString("storage.mysql.username", "root");
                String password = getConfig().getString("storage.mysql.password", "");

                connection = DriverManager.getConnection(
                        "jdbc:mysql://" + host + ":" + port + "/" + database, username, password);

            } else {
                // SQLite setup (default)
                connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + getDataFolder().getAbsolutePath() + "/playerlevels.db");
            }

            // Create tables if they don't exist
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS player_levels (" +
                                "uuid VARCHAR(36) PRIMARY KEY, " +
                                "name VARCHAR(16) NOT NULL, " +
                                "xp DOUBLE NOT NULL DEFAULT 0, " +
                                "level INT NOT NULL DEFAULT 1" +
                                ")"
                );
            }

            getLogger().info("Database connection established using " + storageType);

        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize database connection", e);
        }
    }

    private void startXpCalculationTask() {
        // Schedule task to update player XP every 5 minutes
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::updateAllPlayersXp, 20L * 60, 20L * 300);
    }

    private void updateAllPlayersXp() {
        if (!pluginEnabled) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            calculateAndUpdatePlayerXp(player);
        }
    }

    public void calculateAndUpdatePlayerXp(Player player) {
        if (!pluginEnabled) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            double totalXp = 0;

            // Calculate XP from all configured statistics
            for (StatisticConfig config : statisticConfigs) {
                int statValue;

                try {
                    if (config.getMaterial() != null) {
                        statValue = player.getStatistic(config.getStatistic(), config.getMaterial());
                    } else if (config.getEntityType() != null) {
                        statValue = player.getStatistic(config.getStatistic(), config.getEntityType());
                    } else {
                        statValue = player.getStatistic(config.getStatistic());
                    }

                    totalXp += statValue * config.getXpValue();
                } catch (Exception e) {
                    getLogger().warning("Error getting statistic " + config.getStatistic() + " for player " + player.getName());
                }
            }

            // Update player data
            updatePlayerData(player.getUniqueId(), player.getName(), totalXp);
        });
    }

    private void updatePlayerData(UUID uuid, String name, double xp) {
        int level = calculateLevel(xp);

        // Update memory cache
        PlayerData data = new PlayerData(uuid, name, xp, level);
        playerDataMap.put(uuid, data);

        // Update database
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO player_levels (uuid, name, xp, level) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET name = ?, xp = ?, level = ?")) {

            statement.setString(1, uuid.toString());
            statement.setString(2, name);
            statement.setDouble(3, xp);
            statement.setInt(4, level);
            statement.setString(5, name);
            statement.setDouble(6, xp);
            statement.setInt(7, level);

            statement.executeUpdate();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Error updating player data for " + name, e);
        }
    }

    private void saveAllPlayerData() {
        for (PlayerData data : playerDataMap.values()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE player_levels SET name = ?, xp = ?, level = ? WHERE uuid = ?")) {

                statement.setString(1, data.getName());
                statement.setDouble(2, data.getXp());
                statement.setInt(3, data.getLevel());
                statement.setString(4, data.getUuid().toString());

                statement.executeUpdate();
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Error saving player data for " + data.getName(), e);
            }
        }
    }

    public int calculateLevel(double xp) {
        if (xp <= 0) return 1;

        int level = 1;
        double requiredXp = baseXp;
        double accumulatedXp = 0;

        while (accumulatedXp + requiredXp <= xp) {
            accumulatedXp += requiredXp;
            level++;
            requiredXp = baseXp * Math.pow(xpMultiplier, level - 1);
        }

        return level;
    }

    public double getXpForNextLevel(double currentXp) {
        int currentLevel = calculateLevel(currentXp);
        double xpNeeded = baseXp * Math.pow(xpMultiplier, currentLevel - 1);
        double accumulatedXp = 0;

        for (int i = 1; i < currentLevel; i++) {
            accumulatedXp += baseXp * Math.pow(xpMultiplier, i - 1);
        }

        return accumulatedXp + xpNeeded - currentXp;
    }

    public PlayerData getPlayerData(UUID uuid) {
        // Try to get from cache first
        PlayerData data = playerDataMap.get(uuid);

        // If not in cache, load from database
        if (data == null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name, xp, level FROM player_levels WHERE uuid = ?")) {

                statement.setString(1, uuid.toString());

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        String name = resultSet.getString("name");
                        double xp = resultSet.getDouble("xp");
                        int level = resultSet.getInt("level");

                        data = new PlayerData(uuid, name, xp, level);
                        playerDataMap.put(uuid, data);
                    }
                }
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Error loading player data for " + uuid, e);
            }
        }

        return data;
    }

    public List<PlayerData> getTopPlayers(int limit) {
        List<PlayerData> topPlayers = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid, name, xp, level FROM player_levels ORDER BY level DESC, xp DESC LIMIT ?")) {

            statement.setInt(1, limit);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                    String name = resultSet.getString("name");
                    double xp = resultSet.getDouble("xp");
                    int level = resultSet.getInt("level");

                    topPlayers.add(new PlayerData(uuid, name, xp, level));
                }
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Error loading top players", e);
        }

        return topPlayers;
    }

    public void setPlayerLevel(UUID uuid, String name, int level) {
        double xp = 0;

        // Calculate XP for the given level
        for (int i = 1; i <= level; i++) {
            xp += baseXp * Math.pow(xpMultiplier, i - 1);
        }

        // Update player data
        updatePlayerData(uuid, name, xp);

        // Check for rewards
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            checkAndGiveRewards(player, level);
        }
    }

    public void checkAndGiveRewards(Player player, int level) {
        ConfigurationSection rewardsSection = getConfig().getConfigurationSection("settings.rewards");

        if (rewardsSection != null) {
            for (String key : rewardsSection.getKeys(false)) {
                try {
                    int rewardLevel = Integer.parseInt(key);

                    if (rewardLevel == level) {
                        ConfigurationSection rewardConfig = rewardsSection.getConfigurationSection(key);

                        if (rewardConfig != null) {
                            // Execute commands
                            List<String> commands = rewardConfig.getStringList("commands");
                            for (String command : commands) {
                                String processedCommand = command.replace("%player%", player.getName());
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
                            }

                            // Send message
                            String message = rewardConfig.getString("message", "");
                            if (!message.isEmpty()) {
                                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                            }
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore non-numeric keys
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Load or create player data
        PlayerData data = getPlayerData(player.getUniqueId());

        if (data == null) {
            // New player, calculate initial XP
            calculateAndUpdatePlayerXp(player);
        }
    }

    public boolean isPluginEnabled() {
        return pluginEnabled;
    }

    public double getBaseXp() {
        return baseXp;
    }

    public double getXpMultiplier() {
        return xpMultiplier;
    }

    // Static class for player data
    public static class PlayerData {
        private final UUID uuid;
        private final String name;
        private final double xp;
        private final int level;

        public PlayerData(UUID uuid, String name, double xp, int level) {
            this.uuid = uuid;
            this.name = name;
            this.xp = xp;
            this.level = level;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        public double getXp() {
            return xp;
        }

        public int getLevel() {
            return level;
        }
    }

    // Static class for statistic configuration
    public static class StatisticConfig {
        private final Statistic statistic;
        private final Material material;
        private final EntityType entityType;
        private final double xpValue;

        public StatisticConfig(Statistic statistic, Material material, EntityType entityType, double xpValue) {
            this.statistic = statistic;
            this.material = material;
            this.entityType = entityType;
            this.xpValue = xpValue;
        }

        public Statistic getStatistic() {
            return statistic;
        }

        public Material getMaterial() {
            return material;
        }

        public EntityType getEntityType() {
            return entityType;
        }

        public double getXpValue() {
            return xpValue;
        }
    }
}

// Placeholder expansion class
class PlayerLevelExpansion extends PlaceholderExpansion {
    private final PlayerLevels plugin;

    public PlayerLevelExpansion(PlayerLevels plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "playerlevels";
    }

    @Override
    public @NotNull String getAuthor() {
        return plugin.getDescription().getAuthors().toString();
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (player == null) {
            return "";
        }

        if (identifier.equals("level")) {
            PlayerLevels.PlayerData data = plugin.getPlayerData(player.getUniqueId());
            return data != null ? String.valueOf(data.getLevel()) : "1";
        }

        if (identifier.equals("xp")) {
            PlayerLevels.PlayerData data = plugin.getPlayerData(player.getUniqueId());
            return data != null ? String.format("%.0f", data.getXp()) : "0";
        }

        if (identifier.equals("xp_needed")) {
            PlayerLevels.PlayerData data = plugin.getPlayerData(player.getUniqueId());
            if (data != null) {
                double xpNeeded = plugin.getXpForNextLevel(data.getXp());
                return String.format("%.0f", xpNeeded);
            }
            return String.valueOf(plugin.getBaseXp());
        }

        return null;
    }
}

// Level command class
class LevelCommand implements CommandExecutor {
    private final PlayerLevels plugin;

    public LevelCommand(PlayerLevels plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!plugin.isPluginEnabled()) {
            sender.sendMessage(ChatColor.RED + "PlayerLevels plugin is currently disabled.");
            return true;
        }

        if (args.length == 0) {
            // Show player's level
            if (sender instanceof Player player) {
                showPlayerLevel(sender, player);
            } else {
                sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            }
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("playerlevels.admin")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    return true;
                }

                plugin.loadConfig();
                sender.sendMessage(ChatColor.GREEN + "PlayerLevels configuration reloaded!");
                return true;

            case "set":
                if (!sender.hasPermission("playerlevels.admin")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    return true;
                }

                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /level set <player> <level>");
                    return true;
                }

                String playerName = args[1];
                Player target = Bukkit.getPlayer(playerName);

                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found: " + playerName);
                    return true;
                }

                try {
                    int level = Integer.parseInt(args[2]);
                    if (level < 1) {
                        sender.sendMessage(ChatColor.RED + "Level must be at least 1.");
                        return true;
                    }

                    plugin.setPlayerLevel(target.getUniqueId(), target.getName(), level);
                    sender.sendMessage(ChatColor.GREEN + "Set " + target.getName() + "'s level to " + level);
                    target.sendMessage(ChatColor.GREEN + "Your level has been set to " + level);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid level: " + args[2]);
                }
                return true;

            default:
                // Check if arg[0] is a player name
                Player otherPlayer = Bukkit.getPlayer(args[0]);

                if (otherPlayer != null && sender.hasPermission("playerlevels.others")) {
                    showPlayerLevel(sender, otherPlayer);
                } else {
                    sender.sendMessage(ChatColor.RED + "Unknown command or player not found.");
                }
                return true;
        }
    }

    private void showPlayerLevel(CommandSender sender, Player targetPlayer) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerLevels.PlayerData data = plugin.getPlayerData(targetPlayer.getUniqueId());

            if (data == null) {
                plugin.calculateAndUpdatePlayerXp(targetPlayer);
                data = plugin.getPlayerData(targetPlayer.getUniqueId());
            }

            if (data != null) {
                double xpForNextLevel = plugin.getXpForNextLevel(data.getXp());

                sender.sendMessage(ChatColor.GOLD + "===== " + targetPlayer.getName() + "'s Level =====");
                sender.sendMessage(ChatColor.YELLOW + "Level: " + ChatColor.WHITE + data.getLevel());
                sender.sendMessage(ChatColor.YELLOW + "Total XP: " + ChatColor.WHITE + String.format("%.0f", data.getXp()));
                sender.sendMessage(ChatColor.YELLOW + "XP for next level: " + ChatColor.WHITE + String.format("%.0f", xpForNextLevel));
            } else {
                sender.sendMessage(ChatColor.RED + "Could not retrieve level data for " + targetPlayer.getName());
            }
        });
    }
}

// Level tab completer
class LevelTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission("playerlevels.admin")) {
                completions.add("reload");
                completions.add("set");
            }

            if (sender.hasPermission("playerlevels.others")) {
                completions.addAll(Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .collect(Collectors.toList()));
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("set") && sender.hasPermission("playerlevels.admin")) {
            completions.addAll(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList()));
        }

        return completions;
    }
}

// LevelTop command class
class LevelTopCommand implements CommandExecutor {
    private final PlayerLevels plugin;

    public LevelTopCommand(PlayerLevels plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!plugin.isPluginEnabled()) {
            sender.sendMessage(ChatColor.RED + "PlayerLevels plugin is currently disabled.");
            return true;
        }

        if (!sender.hasPermission("playerlevels.leaderboard")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        int limit = 10;
        if (args.length > 0) {
            try {
                limit = Integer.parseInt(args[0]);
                limit = Math.max(1, Math.min(100, limit)); // Limit between 1 and 100
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid number: " + args[0]);
                return true;
            }
        }

        final int finalLimit = limit;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<PlayerLevels.PlayerData> topPlayers = plugin.getTopPlayers(finalLimit);

            sender.sendMessage(ChatColor.GOLD + "===== Top " + finalLimit + " Players =====");

            if (topPlayers.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No players found.");
            } else {
                int rank = 1;
                for (PlayerLevels.PlayerData data : topPlayers) {
                    sender.sendMessage(ChatColor.YELLOW + "#" + rank + ": " +
                            ChatColor.WHITE + data.getName() + " - " +
                            ChatColor.GREEN + "Level " + data.getLevel() +
                            ChatColor.GRAY + " (" + String.format("%.0f", data.getXp()) + " XP)");
                    rank++;
                }
            }
        });

        return true;
    }
}