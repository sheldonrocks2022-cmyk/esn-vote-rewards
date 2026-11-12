# ESNVoteRewards

Lightweight vote rewards for **Paper** Minecraft servers that use **[NuVotifier](https://github.com/NuVotifier/NuVotifier)**.

- Runs your own console commands as rewards (`give`, economy commands, crate keys, anything)
- Different rewards for the **first vote of the day** and for **extra votes** on other sites, with a daily cap
- **Offline queue**: votes for players who are offline are saved and rewarded when they next join (default: within 7 days)
- **Bedrock / Floodgate friendly**: matches names with a `.` or `*` prefix, case-insensitive, so Bedrock players get their rewards too
- `/vote` shows clickable links to your server-list pages
- Throttled broadcast so one player voting on 6 sites doesn't spam chat
- No hard dependency: Votifier is hooked at runtime, so the plugin loads fine without it
- One small YAML data file, no database

Tested on Paper 1.20.4 with NuVotifier 2.7.3 (a real v2 vote, the offline queue and `/votereward test`). It only uses stable Bukkit/Paper API and is meant for Paper (and forks like Purpur) 1.20.4 and newer, Java 17+.

## Install

1. Install [NuVotifier](https://github.com/NuVotifier/NuVotifier/releases) and set it up with your server lists.
2. Download `ESNVoteRewards-x.y.z.jar` from [Releases](https://github.com/sheldonrocks2022-cmyk/esn-vote-rewards/releases) and put it in `plugins/`.
3. Restart the server, then edit `plugins/ESNVoteRewards/config.yml` and run `/votereward reload`.

## Config

```yaml
timezone: UTC                 # when a new "vote day" starts
first-vote-commands:          # first vote of the day, any site
  - "give {player} diamond 2"
extra-vote-commands:          # each extra site the same day
  - "give {player} iron_ingot 4"
max-extra-votes-per-day: 5
offline-queue-days: 7
broadcast-cooldown-seconds: 30
vote-links:
  - name: "Example list"
    url: "https://example.com/vote"
```

Placeholders: `{player}`, `{service}` (the vote site) and `{count}` (votes rewarded for this player today, including this one). Messages use `&` color codes.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/vote` (`/votes`) | `esnvoterewards.vote` (everyone) | Show the clickable vote links |
| `/votereward reload` | `esnvoterewards.admin` (op) | Reload `config.yml` |
| `/votereward test <player> [site]` | `esnvoterewards.admin` | Simulate a vote without a server list |
| `/votereward stats` | `esnvoterewards.admin` | Votifier hook status and queued offline votes |

## Build

```bash
mvn package      # jar in target/
```

## Host it on ESN

Need a host for your Minecraft server or Discord bot? [ESN Hosting](https://esnoffical.com) runs Minecraft servers and bots 24/7 with a web panel. Bot hosting starts from $0.50/month.

- Website: https://esnoffical.com
- Discord: https://discord.gg/t2tACf3HFq

This plugin started life inside the ESN SMP server (play.esnoffical.com, Java + Bedrock) and was split out as a standalone, general-purpose plugin.

## License

MIT, see [LICENSE](LICENSE). Not affiliated with Mojang, Microsoft, PaperMC or NuVotifier.
