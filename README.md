# SocialBlueprint

SocialBlueprint adds a social reputation layer to a Minecraft Paper server: community honor shapes a player's social status, Killing Psychosis reflects their recent PvP killing, and sanctioned duels distinguish consensual fights from open-world kills. Profiles, rating history, chat prefixes, and cosmetic episodes make these separate metrics visible without turning reputation into combat power.

## Download

**[Download SocialBlueprint v1.0 (.jar)](https://github.com/Dasannn/Blueprint/releases/download/v1.0/SocialBlueprint-1.0.jar)**

[v1.0 release page](https://github.com/Dasannn/Blueprint/releases/tag/v1.0) · [All releases](https://github.com/Dasannn/Blueprint/releases)

Download the companion `.sha256` file from the release assets. Compute the jar's SHA-256 and compare the full 64-character hash with the entry for that jar in the checksum file:

```powershell
Get-FileHash .\SocialBlueprint-1.0.jar -Algorithm SHA256
Get-Content .\SocialBlueprint-1.0.jar.sha256
```

On Linux, for a checksum file containing a hash and filename:

```sh
sha256sum -c SocialBlueprint-1.0.jar.sha256
```

If the file contains only the hash, compare it with `sha256sum SocialBlueprint-1.0.jar`. Install only when the hashes match.

## Requirements

- **Paper 26.3, beta channel**; the source build pins `26.3.build.135-beta`.
- **Java 25**.
- **Vault and a Vault-compatible economy provider**, such as EssentialsX. The plugin disables itself if it cannot find the economy service.

LuckPerms is compatible through standard Bukkit permission checks and is optional. Paper loads the SQLite JDBC library declared by the plugin; no external database server is needed.

## Installation and first start

1. Install Vault and your economy provider on the Paper server.
2. Put the verified jar in `plugins/` and start the server.
3. Check the console for successful enable or configuration/economy errors.
4. Edit `plugins/SocialBlueprint/config.yml`, then run `/status config reload`. The default language is Spanish; use `/status config language en` for English.
5. Run `/status` in-game to open your profile.

The data folder contains:

| File or folder | Purpose |
| --- | --- |
| `config.yml` | Behavior, thresholds, permissions, costs, and effects |
| `messages_en.yml`, `messages_es.yml` | English and Spanish text, tier names, GUI labels, and hallucination lines |
| `socialblueprint.db` | Local SQLite profiles, reputation/kill events, duels, reveals, serenity accounting, and audit history |
| `.defaults/` | Plugin-managed message baselines used when merging upgrades; do not edit |
| `config.yml.bak-<version>` | Configuration backup created when an upgrade adds missing defaults |

Back up the data folder while the server is stopped before upgrading. New configuration defaults are merged into existing files while preserving owner values. Message upgrades preserve customized text and can refresh unchanged shipped translations; obsolete settings are migrated or retired where necessary.

### Importing PlayerStatus

Keep a backup of the old PlayerStatus data and run `/status import`. It reads `plugins/PlayerStatus/config.yml` by default, with `plugins/SocialBlueprint/legacy-config.yml` as a fallback. Use `/status import <file>` to choose another file; `/status admin import [file]` is equivalent.

Each imported score becomes a legacy reputation event. Already imported players are skipped, and imported scores add no Reputation Confidence. UUID identities are preferred. Name-only rows are skipped by default because names can be reused; enabling `legacy-import.trust-name-lookup` explicitly trusts today's name ownership. Review the import's skipped-entry messages before changing that setting.

## Features

**Fairness:** no metric grants or removes combat advantage or material rewards: no damage, health, movement/mining speed, drops, items, or reputation payouts. All effects are cosmetic. Madness hallucinations are private to the affected player; chat presentation and eligible nearby serenity observers are described below. Even the lowest-status player can still chat, build, trade, move, and play. See [constitution §2.1](docs/constitution.md#21-consequences-are-social-never-mechanical).

### Social status and nine tiers

Status is derived from recorded reputation changes. New players begin at `0`, with `Unknown` Confidence and neutral Psychosis. The recognizable colored `[|]` prefixes are configurable; names follow the selected language.

| Tier key | English name | Default status range |
| --- | --- | --- |
| `tier-4` | Criminal | ≤ −50 |
| `tier-3` | Outlaw | −49 to −30 |
| `tier-2` | Delinquent | −29 to −15 |
| `tier-1` | Reckless | −14 to −5 |
| `tier0` | Citizen | −4 to 4 |
| `tier1` | Affable | 5 to 14 |
| `tier2` | Honorable | 15 to 29 |
| `tier3` | Distinguished | 30 to 49 |
| `tier4` | Illustrious | ≥ 50 |

**Reputation Confidence** measures age-weighted evidence from distinct raters: `Unknown`, `Low`, `Established`, or `High`. Repeating one person's opinion does not increase the number of contributors. Administrative adjustments and legacy imports do not count as community evidence.

**Decay** reduces old events' contribution to current status, with a default 30-day half-life. Events remain in history. Confidence has its own independent age weighting and half-life.

### Honor and history

Giving or taking honor changes status by `+1` or `−1` per accepted rating. It costs the actor **500** economy units initially. Ratings issued across all targets in the preceding **1 hour** raise the cost through multipliers `1`, `1.5`, `2`, then `3` (the last multiplier applies thereafter). The same actor-target pair has a **24-hour** cooldown and a cap of **3 positive and 3 negative ratings**, counted separately over **7 days**. Self-rating is rejected.

Taking honor requires a reason; giving honor accepts an optional reason. Reasons are limited to **100 characters** and rendered as inert plain text. Profile GUI buttons prompt for the reason in chat and open a confirmation chest showing the target, exact cost, and reason. Confirmation lasts **60 seconds**; command ratings also support `/status confirm`. Payment and the recorded rating share the same honor path, with compensation handling for failed writes.

The profile chest includes paginated rating history, signed changes, dates, and reasons. Rater names are hidden until revealed for **100** economy units by default; the reveal is remembered for that viewer. Administrative `/status history` provides text history.

### Kills and sanctioned duels

An eligible open-world kill records Killing Psychosis independently of the status penalty. The default penalty is **−1 status**, limited to one penalty per killer/victim pair every **30 minutes** and **10 total automatic status loss per killer in 7 days**. Owners can exempt worlds from the status penalty. These penalty limits do not limit eligible kill evidence or protect a serenity streak.

Duels support one-on-one fights, multiple opponents, and teams using `vs`. All invited participants must accept; challenges expire after **60 seconds**. Kills within the sanctioned duel context change neither status nor Psychosis. Leaving forfeits. Disconnecting within **10 seconds** of combat damage is classified as combat logging, with immediate forfeiture, audit, and a configured broadcast or notification. Other disconnects allow **30 seconds** to reconnect before forfeiture.

### Killing Psychosis and private madness

Eligible non-duel kills count in a rolling **72-hour** window. Zero kills is neutral; madness levels are `Low` (1 kill), `Medium` (2), `High` (5), and `Extreme` (10). Recovery follows kill expiry, independently of status and Confidence.

Medium and higher levels can trigger private episodes, with increasing frequency, quiet intervals, per-effect cooldowns, and session caps:

| Catalogue | Cosmetic presentation |
| --- | --- |
| Sounds and whispers | Creeper fuse sounds, sounds without a visible source, and private chat lines |
| Screen and UI | Title/action-bar flashes, boss bars, and a hurt flash without damage |
| World appearance | Private night sky, particles, temporary equivalent block appearances, and sign text |
| Apparitions | Harmless packet-only hostile mobs and ghosts drawn from the killer's own eligible victim history |
| Fake notices | Self-only join/leave messages and false death messages requiring another visible living player |

Effects respect their minimum madness level and restore temporary appearances during cleanup. Phantom mobs have no real server entity, damage, collision, or drops, and appear outside the viewer's reach; they need a non-Peaceful difficulty, since Minecraft cannot create hostile mobs on Peaceful. The false advancement toast is configured but not shown: Paper 26.3 cannot display one without granting a real advancement. Block illusions preserve interaction behavior. There is no nausea effect, inventory deception, or player `/status effects` opt-out.

**Chat corruption and darkening** affect occasional messages from Medium/High/Extreme speakers at default rates of **10%/25%/40%**, with corruption extents of **20%/35%/50%**. All readers see the same altered body. Alternating messages remain intact, at least half the letters stay unchanged, and messages shorter than 6 letters are skipped. Only episode bodies darken; prefixes and hover remain intact. Social status does not drive corruption or madness effects.

### Serenity, chat, and language

**Serenity is the peaceful direction of Psychosis**, not a fourth metric. After eligible kills leave the rolling window, active peaceful play earns serenity; offline time and idle time do not. Movement with player input, mining, building, accepted interactions, and inventory actions qualify; chat and passive movement do not. The idle timeout defaults to **300 seconds**, and an `afk` metadata flag pauses accrual. An eligible non-duel kill resets accumulated serenity, even when a status penalty is capped or exempt.

The curve is `ceiling × (2x − x²)`, where `x` is credited active hours divided by the hours needed to reach the ceiling, clamped to `0…1`. Defaults reach a ceiling of **100** after **100 active hours**, with diminishing returns. Serene sounds, particles, and harmless animal apparitions can be shared with eligible nearby observers (default range **16 blocks**); dawn remains private. No serene title is shown.

Chat carries the tier prefix and a name hover with status, tier, Confidence, Psychosis, serenity, and how many distinct players rated them. If another plugin owns the chat renderer, SocialBlueprint leaves that renderer in control, so its chat presentation may not appear.

English and Spanish message files are bundled. Colors use Essentials-style `&` codes, including hex colors. Owners can edit behavior and messages live in-game. The GitHub updater stages verified releases for the next restart.

## Commands

`/pstatus` and `/reputation` alias `/status`. Permissions below are the default nodes; mappings are configurable. Honor and duel actions require a player. Console can inspect a named profile, use configuration/admin/import/update commands, and read named text history.

| Syntax | Action | Permission |
| --- | --- | --- |
| `/status` | Open your profile chest | `socialblueprint.show` |
| `/status <player>` | View another profile (text for console) | `socialblueprint.show.others` |
| `/status info <player>` | Alias for viewing another profile | `socialblueprint.show.others` |
| `/status psychosis [player]` | Show Psychosis and serenity | `socialblueprint.show` for self; `socialblueprint.show.others` with a target |
| `/status give <player> [reason]` | Preview a positive rating; alias: `trust` | `socialblueprint.give` |
| `/status take <player> <reason>` | Preview a negative rating; aliases: `remove`, `distrust` | `socialblueprint.take` |
| `/status <player> + [reason]` | Legacy positive-rating syntax | `socialblueprint.give` |
| `/status <player> - <reason>` | Legacy negative-rating syntax | `socialblueprint.take` |
| `/status confirm` | Confirm your pending rating; rechecks its action permission | `socialblueprint.give` or `socialblueprint.take` |
| `/status history [player]` | Administrative text history; console must name a target | `socialblueprint.admin.adjust` (console is always allowed) |
| `/status duel <opponent> [opponent…]` | Challenge one or more opponents | `socialblueprint.duel` |
| `/status duel <ally…> vs <opponent…>` | Team challenge; sender joins the first side | `socialblueprint.duel` |
| `/status duel accept [challenger]` | Accept a challenge; alias: `/status accept [challenger]` | `socialblueprint.duel` |
| `/status duel deny [challenger]` | Decline a challenge; alias: `/status deny [challenger]` | `socialblueprint.duel` |
| `/status duel leave` | Forfeit; alias: `/status leave` | `socialblueprint.duel` |
| `/status admin give <player> [amount]` | Add status; amount defaults to 1 | `socialblueprint.admin.adjust` |
| `/status admin take <player> [amount]` | Subtract status; amount defaults to 1 | `socialblueprint.admin.adjust` |
| `/status admin reset <player>` | Reset status through a compensating event | `socialblueprint.admin.adjust` |
| `/status import [file]` | Import PlayerStatus; alias: `/status admin import [file]` | `socialblueprint.admin.import` |
| `/status config [get] <key>` | Read a supported behavior or message key | `socialblueprint.admin.config` |
| `/status config [set] <key> <value>` | Validate, save, and apply a supported key | `socialblueprint.admin.config` |
| `/status config reload` | Reload YAML files | `socialblueprint.admin.config` |
| `/status version` | Check running and available versions | `socialblueprint.version` |
| `/status update` | Download and stage a verified update | `socialblueprint.admin.update` |

## Permissions

| Node | Default | Grants |
| --- | --- | --- |
| `socialblueprint.show` | Everyone | Own profile and Psychosis |
| `socialblueprint.show.others` | Everyone | Other profiles and Psychosis |
| `socialblueprint.give` | Everyone | Give honor |
| `socialblueprint.take` | Everyone | Take honor |
| `socialblueprint.view` | Everyone | Declared compatibility node; current GUI access checks `show` / `show.others` |
| `socialblueprint.duel` | Everyone | Duel participation |
| `socialblueprint.version` | Everyone | Version checks |
| `socialblueprint.admin.adjust` | Operators | Status adjustments and player text-history access |
| `socialblueprint.admin.config` | Operators | Configuration and message editing/reload |
| `socialblueprint.admin.import` | Operators | Legacy import |
| `socialblueprint.admin.update` | Operators | Stage updates |
| `socialblueprint.admin` | Operators | All four administrative nodes |
| `socialblueprint.*` | Operators | All declared SocialBlueprint action permissions |

Legacy grants remain supported:

| Legacy node | Supported action |
| --- | --- |
| `pstatus.show` | Own profile; version check compatibility |
| `pstatus.showOtherPlayers` | Other profiles |
| `pstatus.giveReputation` | Give and take honor |
| `pstatus.addRemoveRep` | Take honor and administrative adjustments |
| `pstatus.setReputation` | Administrative adjustments |
| `pstatus.viewReputation` | Legacy mapping for the declared `view` action; no current GUI gate uses it |
| `pstatus.admin` | Administrative permissions and version checks |

Declared compatibility aliases also include `socialblueprint.show-others`, `socialblueprint.give-reputation`, `socialblueprint.take-reputation`, and `socialblueprint.admin-adjust`. Checks consult configured nodes, compatibility forms, parent grants, and legacy grants; changing a mapping does not retire existing grants.

## Configuration

See the complete [default config.yml](src/main/resources/config.yml) for every effect and sound setting. These are useful starting points:

| Section / keys | Shipped defaults | Purpose |
| --- | --- | --- |
| `language`, `chat-prefix` | `es`, `&8[&bSocialBlueprint&8]&r ` | Active language and plugin notification prefix |
| `tiers.<tier>.threshold`, `.prefix` | −50/−30/−15/−5/0/5/15/30/50; colored `[\|]` ladder | Tier boundaries and appearance |
| `confidence.half-life`; `.low-threshold`, `.established-threshold`, `.high-threshold` | `30d`; `1.0`, `5.0`, `15.0` | Evidence aging and levels |
| `decay.enabled`, `.half-life`, `.floor`, `.cache-ttl` | `true`, `30d`, `0.0`, `60s` | Status aging and cache lifetime |
| `honor.cost`, `.multipliers`, `.multiplier-window` | `500.0`, `[1.0, 1.5, 2.0, 3.0]`, `1h` | Rating price progression |
| `honor.cooldown-per-pair`, `.max-per-target`, `.cap-window` | `24h`, `3`, `7d` | Pair frequency and signed caps |
| `history.reveal-cost` | `100.0` | Reveal a rater's name |
| `kill-penalty.delta`, `.pair-cooldown`, `.cap-window`, `.max-loss`, `.exempt-worlds` | `-1`, `30m`, `7d`, `10`, `[]` | Automatic status penalty; delta `0` disables it |
| `psychosis.window`; `.medium-threshold`, `.high-threshold`, `.extreme-threshold` | `72h`; `2`, `5`, `10` | Rolling kill evidence |
| `psychosis.chat.<level>-rate`, `.<level>-extent` | Rates `10/25/40`, extents `20/35/50` | Corruption probability and amount |
| `psychosis.chat.<level>-colour`, `.min-letters` | `#AAAAAA/#666666/#303030`, `6` | Episode body color and minimum text length |
| `psychosis.serenity.ceiling`, `.active-hours-to-ceiling`, `.idle-timeout-seconds` | `100`, `100`, `300` | Peaceful progression |
| `duel.challenge-timeout`, `.attack-context-window` | `60s`, `30s` | Challenge expiry and recorded attack context |
| `duel.disconnect.combat-log-window`, `.reconnect-grace-period`, `.action` | `10s`, `30s`, `broadcast` | Disconnect classification and social response (`broadcast` or `notify`) |
| `effects.check-interval`, `.max-episode-ticks` | `1s`, `200` | Scheduler and bounded sound episodes |
| `effects.episodes.<level>.interval-ticks` | `2400/1200/400` | Madness episode cadence |
| `effects.quiet-interval.<level>` | `2m/1m/20s` | Silence floors between madness episodes |
| `effects.<effect>.*` | Enabled catalogue entries; level floors, cooldowns, caps, and durations vary | Individual cosmetic episodes |
| `effects.serenity.observer-range-blocks`, `.episodes.interval-ticks` | `16`, `6000` | Nearby audience and serene cadence |
| `sounds.*` | Named sound slots and layered lists | Keys, volume, pitch, category, and layer delays; empty keys silence sounds |
| `permissions.*` | Nodes in the permissions table | Action-to-node mappings |
| `legacy-import.trust-name-lookup` | `false` | Whether to trust name-only legacy identities |
| `update.check-on-startup`, `.auto-download`, `.channel` | `true`, `false`, `stable` | Startup checks, opt-in download, and release channel |
| `update.repository`, `.api-url`, `.max-download-bytes` | `Dasannn/Blueprint`, `https://api.github.com`, `10485760` | Release source and 10 MiB asset limit |

Duration keys accept `d`, `h`, `m`, and `s`; a bare number means seconds. Keys ending in `-ticks` use server ticks (normally 20 per second).

### Live editing and messages

```text
/status config get honor.cost
/status config set honor.cost 750
/status config language en
/status config tiers.tier1.prefix &7[&a|&7]
/status config reload
```

Live edits validate before applying and persist to disk. Unsupported keys and invalid values are rejected. Manual YAML edits take effect with `/status config reload`.

The [English](src/main/resources/messages_en.yml) and [Spanish](src/main/resources/messages_es.yml) files hold tier/metric names, command replies, chat hover text, GUI labels/prompts, updater messages, and effect text. Known message keys are editable through the same command in the active language, for example:

```text
/status config tiers.tier1 Affable
/status config effects.private-chat.custom-lines ['&8The walls remember.', '&7Something is watching.']
```

Operators can add custom hallucinations in `effects.private-chat.custom-lines` (empty by default). Other catalogue lists include `effects.private-chat.lines`, `effects.sign.lines`, `effects.screen-flash.lines`, `effects.advancement-toast.lines`, and `effects.boss-bar.lines`. Fake-connection and false-death templates live under `effects.fake-connection.join`, `.leave`, and `effects.false-death.line`.

Custom/private-chat, toast, and boss-bar lines must be nonblank, validly colored, single-line, noninteractive text: no control characters, line separators, or click/hover/action directives. Private chat is limited by `effects.private-chat.max-visible-length` (default **160 visible characters**); toast and boss-bar lines are bounded at 160. Validation reserves 16 characters for `{player}` in these lists. Custom lines additionally reject English/Spanish server-notice, moderation, permission, economy, and reward wording and currency symbols, preventing imitation of authoritative notices. Load and live-edit errors identify the offending key/list entry. Edit both language files when providing text for both languages.

## Updating

1. Run `/status version` to check the running version against GitHub releases.
2. Run `/status update` with administrative permission to download the selected release.
3. Restart the server after the success message.

`update.channel: stable` checks the latest stable release; `beta` also considers prereleases. Startup checks are enabled, but automatic downloading is off by default. Downloads require SHA-256 verification and JAR validation before staging. Missing or mismatched checksums reject the update.

The verified jar goes into Paper's update folder, normally `plugins/update/`, using the current plugin jar's filename when available. The running jar is left in place until restart. Network or release-check failures are reported without preventing startup.

## Building from source

Use **JDK 25** and the checked-in Maven wrapper:

```sh
./mvnw clean verify
```

On Windows:

```powershell
.\mvnw.cmd clean verify
```

The jar is `target/SocialBlueprint-1.0.jar`. On systems where in-process javac fails, use `./mvnw clean verify -Dmaven.compiler.fork=true`.

## Roadmap — not shipped

The spec's [Later releases](docs/spec.md#12-later-releases) list includes older entries that are now implemented: reasons/history, profile cost confirmation, Confidence, and decay are described above. Remaining ideas include:

- Public comments with a mandatory-reason workflow and the Bobba profanity filter beyond the current rating reasons.
- Vouching backed by a monetary guarantee.
- Trade warnings, CoreProtect, land-claim integrations, and a reputation event API.
- Deaths as a Psychosis input; direction and anti-abuse rules remain undecided.
- Votes affecting Psychosis or serenity; this requires a constitutional amendment before implementation.
- More configurable ghost labels, false-death templates, particle choices, and serene apparition kinds beyond the current settings.

These are plans, not v1.0 capabilities or release commitments.

## License

No `LICENSE` file is present. The repository's license is not yet specified.
