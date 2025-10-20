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

import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class PlayerLevels extends JavaPlugin implements Listener {

    private Map<UUID, PlayerData> playerDataMap = new HashMap<>();
    private Map<UUID, Long> xpBoostMap = new HashMap<>();
    private Connection connection;
    private String storageType;
    private double baseXp;
    private double xpMultiplier;
    private List<StatisticConfig> statisticConfigs;
    private boolean pluginEnabled;
    private boolean levelUpBroadcast;
    private String levelUpMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this)
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().warning("PlaceholderAPI not found! Placeholders will not work.");
        } else {
            new PlayerLevelExpansion(this).register();
        }

        getCommand("level").setExecutor(new LevelCommand(this));
        getCommand("level").setTabCompleter(new LevelTabCompleter());
        getCommand("leveltop").setExecutor(new LevelTopCommand(this));

        loadConfig();
        initializeDatabase();
        startXpCalculationTask();

        getLogger().info("PlayerLevels plugin enabled with enhanced features!");
    }

    @Override
    public void onDisable() {
        saveAllPlayerData();
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

        pluginEnabled = config.getBoolean("settings.enable-plugin", true);
        baseXp = config.getDouble("settings.levels.base-xp", 100);
        xpMultiplier = config.getDouble("settings.levels.xp-multiplier", 1.5);
        storageType = config.getString("storage.type", "sqlite").toLowerCase();
        levelUpBroadcast = config.getBoolean("settings.level-up-broadcast", true);
        levelUpMessage = config.getString("settings.level-up-message", "&6%player% &ehas reached &aLevel %level%&e!");

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
                String host = getConfig().getString("storage.mysql.host", "localhost");
                int port = getConfig().getInt("storage.mysql.port", 3306);
                String database = getConfig().getString("storage.mysql.database", "minecraft");
                String username = getConfig().getString("storage.mysql.username", "root");
                String password = getConfig().getString("storage.mysql.password", "");

                connection = DriverManager.getConnection(
                        "jdbc:mysql://" + host + ":" + port + "/" + database, username, password);

            } else {
                connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + getDataFolder().getAbsolutePath() + "/playerlevels.db");
            }

            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS player_levels (" +
                                "uuid VARCHAR(36) PRIMARY KEY, " +
                                "name VARCHAR(16) NOT NULL, " +
                                "xp DOUBLE NOT NULL DEFAULT 0, " +
                                "level INT NOT NULL DEFAULT 1" +
                                ")"
                );

                statement.execute(
                        "CREATE TABLE IF NOT EXISTS player_awards (" +
                                "uuid VARCHAR(36) NOT NULL, " +
                                "award_id VARCHAR(50) NOT NULL, " +
                                "awarded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                                "PRIMARY KEY (uuid, award_id)" +
                                ")"
                );
            }

            getLogger().info("Database connection established using " + storageType);

        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize database connection", e);
        }
    }

    private void startXpCalculationTask() {
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

            // Apply XP boost if active
            if (hasActiveXpBoost(player.getUniqueId())) {
                double boostMultiplier = getConfig().getDouble("settings.xp-boost.multiplier", 2.0);
                totalXp *= boostMultiplier;
            }

            updatePlayerData(player.getUniqueId(), player.getName(), totalXp);
        });
    }

    private void updatePlayerData(UUID uuid, String name, double xp) {
        int level = calculateLevel(xp);
        PlayerData oldData = playerDataMap.get(uuid);
        int oldLevel = oldData != null ? oldData.getLevel() : 1;

        PlayerData data = new PlayerData(uuid, name, xp, level);
        playerDataMap.put(uuid, data);

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

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && level > oldLevel) {
            // Level up event
            if (levelUpBroadcast) {
                String message = levelUpMessage
                        .replace("%player%", player.getName())
                        .replace("%level%", String.valueOf(level));
                Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
            }

            checkAndGiveRewards(player, level);
            checkAndGiveAwards(player, level);
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

    public String getProgressBar(double currentXp, int currentLevel) {
        double nextLevelXp = baseXp * Math.pow(xpMultiplier, currentLevel - 1);
        double accumulatedXp = 0;

        for (int i = 1; i < currentLevel; i++) {
            accumulatedXp += baseXp * Math.pow(xpMultiplier, i - 1);
        }

        double progress = (currentXp - accumulatedXp) / nextLevelXp;
        progress = Math.min(Math.max(progress, 0), 1);
        int percentage = (int) (progress * 100);

        int barLength = 20;
        int filled = (int) (barLength * progress);

        StringBuilder bar = new StringBuilder(ChatColor.GREEN + "");
        for (int i = 0; i < barLength; i++) {
            if (i < filled) {
                bar.append("█");
            } else {
                bar.append(ChatColor.GRAY).append("▒").append(ChatColor.GREEN);
            }
        }
        bar.append(ChatColor.YELLOW).append(" ").append(percentage).append("%");

        return bar.toString();
    }

    public PlayerData getPlayerData(UUID uuid) {
        PlayerData data = playerDataMap.get(uuid);

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

    public List<PlayerData> getTopPlayersWithOffset(int limit, int offset) {
        List<PlayerData> topPlayers = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid, name, xp, level FROM player_levels ORDER BY level DESC, xp DESC LIMIT ? OFFSET ?")) {

            statement.setInt(1, limit);
            statement.setInt(2, offset);

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
            getLogger().log(Level.SEVERE, "Error loading top players with offset", e);
        }

        return topPlayers;
    }

    public int getPlayerRank(UUID uuid) {
        PlayerData playerData = getPlayerData(uuid);
        if (playerData == null) {
            return -1;
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) + 1 as rank FROM player_levels WHERE level > ? OR (level = ? AND xp > ?)")) {

            statement.setInt(1, playerData.getLevel());
            statement.setInt(2, playerData.getLevel());
            statement.setDouble(3, playerData.getXp());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("rank");
                }
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Error getting player rank for " + uuid, e);
        }

        return -1;
    }

    public int getTotalPlayersCount() {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM player_levels")) {

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Error getting total players count", e);
        }
        return 0;
    }

    public void setPlayerLevel(UUID uuid, String name, int level) {
        double xp = 0;

        for (int i = 1; i <= level; i++) {
            xp += baseXp * Math.pow(xpMultiplier, i - 1);
        }

        updatePlayerData(uuid, name, xp);

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
                            List<String> commands = rewardConfig.getStringList("commands");
                            for (String command : commands) {
                                String processedCommand = command.replace("%player%", player.getName());
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
                            }

                            String message = rewardConfig.getString("message", "");
                            if (!message.isEmpty()) {
                                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                            }
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    public void checkAndGiveAwards(Player player, int level) {
        ConfigurationSection awardsSection = getConfig().getConfigurationSection("settings.awards");
        if (awardsSection == null) return;

        int rank = getPlayerRank(player.getUniqueId());

        for (String awardId : awardsSection.getKeys(false)) {
            ConfigurationSection awardConfig = awardsSection.getConfigurationSection(awardId);
            if (awardConfig == null) continue;

            if (hasPlayerAward(player.getUniqueId(), awardId)) continue;

            String requirement = awardConfig.getString("requirement", "");
            boolean meetsRequirement = false;

            if (requirement.startsWith("level=")) {
                int reqLevel = Integer.parseInt(requirement.split("=")[1]);
                meetsRequirement = level >= reqLevel;
            } else if (requirement.startsWith("rank=")) {
                int reqRank = Integer.parseInt(requirement.split("=")[1]);
                meetsRequirement = rank <= reqRank && rank > 0;
            }

            if (meetsRequirement) {
                giveAward(player, awardId, awardConfig);
            }
        }
    }

    private boolean hasPlayerAward(UUID uuid, String awardId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM player_awards WHERE uuid = ? AND award_id = ?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, awardId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Error checking award for " + uuid, e);
            return false;
        }
    }

    private void giveAward(Player player, String awardId, ConfigurationSection awardConfig) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO player_awards (uuid, award_id) VALUES (?, ?)")) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, awardId);
            statement.executeUpdate();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Error giving award " + awardId + " to " + player.getName(), e);
            return;
        }

        String message = awardConfig.getString("message", "");
        if (!message.isEmpty()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }

        List<String> commands = awardConfig.getStringList("commands");
        for (String command : commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }
    }

    public List<String> getPlayerAwards(UUID uuid) {
        List<String> awards = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT award_id FROM player_awards WHERE uuid = ? ORDER BY awarded_at DESC")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    awards.add(rs.getString("award_id"));
                }
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Error fetching awards for " + uuid, e);
        }
        return awards;
    }

    public void giveXpBoost(UUID uuid, long durationMinutes) {
        long expiryTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000);
        xpBoostMap.put(uuid, expiryTime);
    }

    public boolean hasActiveXpBoost(UUID uuid) {
        if (!xpBoostMap.containsKey(uuid)) return false;
        long expiryTime = xpBoostMap.get(uuid);
        if (System.currentTimeMillis() > expiryTime) {
            xpBoostMap.remove(uuid);
            return false;
        }
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = getPlayerData(player.getUniqueId());

        if (data == null) {
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
        if (player != null) {
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

            if (identifier.equals("progress")) {
                PlayerLevels.PlayerData data = plugin.getPlayerData(player.getUniqueId());
                if (data != null) {
                    return plugin.getProgressBar(data.getXp(), data.getLevel());
                }
                return "";
            }

            if (identifier.equals("rank")) {
                int rank = plugin.getPlayerRank(player.getUniqueId());
                return rank > 0 ? String.valueOf(rank) : "N/A";
            }

            if (identifier.equals("awards")) {
                List<String> awards = plugin.getPlayerAwards(player.getUniqueId());
                return String.valueOf(awards.size());
            }
        }

        if (identifier.startsWith("top_")) {
            String[] parts = identifier.split("_");
            if (parts.length >= 3) {
                try {
                    int position = Integer.parseInt(parts[1]);
                    String type = parts[2];

                    List<PlayerLevels.PlayerData> topPlayers = plugin.getTopPlayers(position);

                    if (position > 0 && position <= topPlayers.size()) {
                        PlayerLevels.PlayerData data = topPlayers.get(position - 1);

                        switch (type.toLowerCase()) {
                            case "name":
                                return data.getName();
                            case "level":
                                return String.valueOf(data.getLevel());
                            case "xp":
                                return String.format("%.0f", data.getXp());
                            case "rank":
                                return String.valueOf(position);
                        }
                    } else {
                        switch (type.toLowerCase()) {
                            case "name":
                                return "---";
                            case "level":
                            case "xp":
                                return "0";
                            case "rank":
                                return String.valueOf(position);
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (identifier.equals("total_players")) {
            return String.valueOf(plugin.getTotalPlayersCount());
        }

        return null;
    }
}

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

            case "awards":
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
                    return true;
                }

                Player targetPlayer = player;
                if (args.length > 1 && sender.hasPermission("playerlevels.admin")) {
                    targetPlayer = Bukkit.getPlayer(args[1]);
                    if (targetPlayer == null) {
                        sender.sendMessage(ChatColor.RED + "Player not found: " + args[1]);
                        return true;
                    }
                }

                showPlayerAwards(sender, targetPlayer);
                return true;

            case "boost":
                if (!sender.hasPermission("playerlevels.admin")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    return true;
                }

                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /level boost <player> <minutes>");
                    return true;
                }

                Player boostTarget = Bukkit.getPlayer(args[1]);
                if (boostTarget == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found: " + args[1]);
                    return true;
                }

                try {
                    long minutes = Long.parseLong(args[2]);
                    plugin.giveXpBoost(boostTarget.getUniqueId(), minutes);
                    sender.sendMessage(ChatColor.GREEN + "Gave " + boostTarget.getName() + " XP boost for " + minutes + " minutes!");
                    boostTarget.sendMessage(ChatColor.GOLD + "✨ You received an XP boost for " + minutes + " minutes!");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid duration: " + args[2]);
                }
                return true;

            default:
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
                String progressBar = plugin.getProgressBar(data.getXp(), data.getLevel());
                int rank = plugin.getPlayerRank(targetPlayer.getUniqueId());

                sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════════");
                sender.sendMessage(ChatColor.GOLD + "  📊 " + ChatColor.YELLOW + targetPlayer.getName() + "'s Level Stats");
                sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════════");
                sender.sendMessage(ChatColor.YELLOW + "Level: " + ChatColor.WHITE + data.getLevel() +
                        ChatColor.GRAY + " | Rank: " + ChatColor.AQUA + "#" + rank);
                sender.sendMessage(ChatColor.YELLOW + "Total XP: " + ChatColor.WHITE + String.format("%.0f", data.getXp()));
                sender.sendMessage(ChatColor.YELLOW + "Next Level: " + ChatColor.WHITE + String.format("%.0f", xpForNextLevel) + " XP");
                sender.sendMessage(ChatColor.YELLOW + "Progress: " + progressBar);

                if (plugin.hasActiveXpBoost(targetPlayer.getUniqueId())) {
                    sender.sendMessage(ChatColor.GOLD + "✨ XP Boost Active!");
                }

                List<String> awards = plugin.getPlayerAwards(targetPlayer.getUniqueId());
                sender.sendMessage(ChatColor.YELLOW + "Awards: " + ChatColor.WHITE + awards.size());
                sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════════");
            } else {
                sender.sendMessage(ChatColor.RED + "Could not retrieve level data for " + targetPlayer.getName());
            }
        });
    }

    private void showPlayerAwards(CommandSender sender, Player targetPlayer) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> awards = plugin.getPlayerAwards(targetPlayer.getUniqueId());

            sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════════");
            sender.sendMessage(ChatColor.GOLD + "  🏆 " + ChatColor.YELLOW + targetPlayer.getName() + "'s Awards");
            sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════════");

            if (awards.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No awards earned yet.");
            } else {
                for (String awardId : awards) {
                    ConfigurationSection awardConfig = plugin.getConfig().getConfigurationSection("settings.awards." + awardId);
                    String displayName = awardConfig != null ? awardConfig.getString("display-name", awardId) : awardId;
                    sender.sendMessage(ChatColor.YELLOW + "• " + ChatColor.WHITE + displayName);
                }
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GRAY + "Total: " + ChatColor.WHITE + awards.size() + " awards");
            }
            sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════════");
        });
    }
}

class LevelTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission("playerlevels.admin")) {
                completions.add("reload");
                completions.add("set");
                completions.add("boost");
            }

            completions.add("awards");

            if (sender.hasPermission("playerlevels.others")) {
                completions.addAll(Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .collect(Collectors.toList()));
            }
        } else if (args.length == 2) {
            if ((args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("boost"))
                    && sender.hasPermission("playerlevels.admin")) {
                completions.addAll(Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .collect(Collectors.toList()));
            } else if (args[0].equalsIgnoreCase("awards") && sender.hasPermission("playerlevels.admin")) {
                completions.addAll(Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .collect(Collectors.toList()));
            }
        }

        return completions;
    }
}

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
        int page = 1;

        if (args.length > 0) {
            try {
                limit = Integer.parseInt(args[0]);
                limit = Math.max(1, Math.min(100, limit));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid number: " + args[0]);
                return true;
            }
        }

        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
                page = Math.max(1, page);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid page number: " + args[1]);
                return true;
            }
        }

        final int finalLimit = limit;
        final int finalPage = page;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int totalPlayers = plugin.getTotalPlayersCount();
            int totalPages = (int) Math.ceil((double) totalPlayers / finalLimit);

            int offset = (finalPage - 1) * finalLimit;
            int startRank = offset + 1;

            List<PlayerLevels.PlayerData> topPlayers = plugin.getTopPlayersWithOffset(finalLimit, offset);

            sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════════");
            sender.sendMessage(ChatColor.GOLD + "    🏆 " + ChatColor.YELLOW + "Player Levels Leaderboard" + ChatColor.GOLD + " 🏆");
            sender.sendMessage(ChatColor.GRAY + "       Page " + finalPage + " of " + totalPages);
            sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════════");

            if (topPlayers.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No players found on this page.");
            } else {
                for (int i = 0; i < topPlayers.size(); i++) {
                    PlayerLevels.PlayerData data = topPlayers.get(i);
                    int rank = startRank + i;

                    String rankDisplay;
                    ChatColor nameColor;

                    switch (rank) {
                        case 1:
                            rankDisplay = ChatColor.GOLD + "👑 #1";
                            nameColor = ChatColor.GOLD;
                            break;
                        case 2:
                            rankDisplay = ChatColor.GRAY + "🥈 #2";
                            nameColor = ChatColor.GRAY;
                            break;
                        case 3:
                            rankDisplay = ChatColor.GOLD + "🥉 #3";
                            nameColor = ChatColor.GOLD;
                            break;
                        default:
                            rankDisplay = ChatColor.YELLOW + "#" + rank;
                            nameColor = ChatColor.WHITE;
                            break;
                    }

                    sender.sendMessage(
                            rankDisplay + ChatColor.DARK_GRAY + " | " +
                                    nameColor + ChatColor.BOLD + data.getName() + ChatColor.RESET +
                                    ChatColor.DARK_GRAY + " - " +
                                    ChatColor.GREEN + "Level " + data.getLevel() + " " +
                                    ChatColor.DARK_GRAY + "(" + ChatColor.GRAY + String.format("%.0f", data.getXp()) + " XP" + ChatColor.DARK_GRAY + ")"
                    );
                }
            }

            sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════════");

            if (totalPages > 1) {
                StringBuilder navigation = new StringBuilder(ChatColor.GRAY + "Use ");
                navigation.append(ChatColor.YELLOW).append("/leveltop ").append(finalLimit).append(" <page>");
                navigation.append(ChatColor.GRAY).append(" to view other pages");
                sender.sendMessage(navigation.toString());
            }

            if (sender instanceof Player) {
                Player player = (Player) sender;
                int playerRank = plugin.getPlayerRank(player.getUniqueId());

                if (playerRank > 0 && (playerRank < startRank || playerRank > startRank + topPlayers.size() - 1)) {
                    PlayerLevels.PlayerData playerData = plugin.getPlayerData(player.getUniqueId());
                    if (playerData != null) {
                        sender.sendMessage(
                                ChatColor.GRAY + "Your rank: " +
                                        ChatColor.YELLOW + "#" + playerRank +
                                        ChatColor.GRAY + " - Level " + ChatColor.GREEN + playerData.getLevel() +
                                        ChatColor.GRAY + " (" + String.format("%.0f", playerData.getXp()) + " XP)"
                        );
                    }
                }
            }
        });

        return true;
    }
}