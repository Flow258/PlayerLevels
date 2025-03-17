# PlayerLevels Plugin

A Minecraft plugin that introduces player levels based on statistics tracking

## Features

- **Dynamic Leveling**: Players gain levels as they play based on their in-game statistics
- **Balanced Scaling**: Higher levels require exponentially more XP
- **Configurable Stats**: Server owners choose which stats affect leveling
- **Level Rewards**: Automatically reward players when they reach certain levels
- **PlaceholderAPI Integration**: Use player level information in other plugins
- **Leaderboard**: View top players by level

## Requirements

- Paper 1.21.3 or newer
- [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) with Statistic Expansion

## Installation

1. Download the plugin JAR from the releases page
2. Place the JAR in your server's `plugins` folder
3. Restart your server
4. Make sure PlaceholderAPI is installed
5. Install the Statistic expansion: `/papi ecloud download Statistic` and `/papi reload`
6. Configure the plugin to your liking in `plugins/PlayerLevels/config.yml`

## Configuration

The plugin is highly configurable. Here's a breakdown of the main configuration sections:

### Basic Settings

```yaml
settings:
  enable-plugin: true
  
  levels:
    base-xp: 100       # Base XP required for level 1
    xp-multiplier: 1.5 # XP increase per level (exponential scaling)
```

### Statistics Configuration

You can configure which statistics count towards player levels and how much XP they provide:

```yaml
statistics:
  1:
    statistic: "MINE_BLOCK"
    material: "STONE"
    xp-value: 5      # Gain 5 XP per stone mined
```

The `statistic` field should be a valid Minecraft statistic name. For statistics related to blocks, include the `material` field. For statistics related to entities, include the `entity` field.

Negative XP values can be used to penalize certain actions (like deaths).

### Rewards

Configure rewards for reaching specific levels:

```yaml
rewards:
  5:
    commands:
      - "give %player% diamond 1"
    message: "&aYou reached Level 5! Enjoy a diamond!"
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/level` | View your current level | `playerlevels.use` |
| `/level <player>` | View another player's level | `playerlevels.others` |
| `/level reload` | Reload the plugin configuration | `playerlevels.admin` |
| `/level set <player> <level>` | Set a player's level | `playerlevels.admin` |
| `/leveltop [limit]` | Show the leaderboard of highest levels | `playerlevels.leaderboard` |

## Placeholders

The plugin provides the following PlaceholderAPI placeholders:

- `%playerlevels_level%`: Player's current level
- `%playerlevels_xp%`: Player's total XP points
- `%playerlevels_xp_needed%`: XP needed for next level

## Storage

By default, the plugin uses SQLite for data storage. You can switch to MySQL by changing the configuration:

```yaml
storage:
  type: "mysql"
  mysql:
    host: "localhost"
    port: 3306
    database: "minecraft"
    username: "root"
    password: ""
```

## License

This plugin is released under the MIT License. See the LICENSE file for details.
