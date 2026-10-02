# SocialBlueprint

SocialBlueprint adds a social reputation layer to a Minecraft Paper server: community honor shapes a player's social status, Psychosis tracks a persistent mental state from madness through Neutral to serenity, and sanctioned duels distinguish consensual fights from open-world kills. Profiles, rating history, chat prefixes, and cosmetic episodes make these separate metrics visible without turning reputation into combat power.

## Download

**[Download SocialBlueprint v2.0 (.jar)](https://github.com/Dasannn/Blueprint/releases/download/v2.0/SocialBlueprint-2.0.jar)**

[v2.0 release page](https://github.com/Dasannn/Blueprint/releases/tag/v2.0) · [All releases](https://github.com/Dasannn/Blueprint/releases)

Download the companion `.sha256` file from the release assets. Compute the jar's SHA-256 and compare the full 64-character hash with the entry for that jar in the checksum file:

```powershell
Get-FileHash .\SocialBlueprint-2.0.jar -Algorithm SHA256
Get-Content .\SocialBlueprint-2.0.jar.sha256
```

On Linux, for a checksum file containing a hash and filename:

```sh
sha256sum -c SocialBlueprint-2.0.jar.sha256
```

If the file contains only the hash, compare it with `sha256sum SocialBlueprint-2.0.jar`. Install only when the hashes match.

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
| `socialblueprint.db` | Local SQLite profiles, reputation/kill events, mental state and its event log, duels, reveals, and audit history |
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

Giving or taking honor changes status by `+1` or `−1` per accepted rating. It costs the actor **500** economy units initially. Ratings issued across all targets in the preceding **1 hour** raise the cost through multipliers `1`, `1.5`, `2`, then `3` (the last multiplier applies thereafter). The same actor-target pair has a **24-hour** cooldown and a cap of **3 positive and 3 negative ratings**, counted separately over **7 days**. Self-rating is rejected. Commands and GUI rejections show the time left until another rating is allowed; profile rating buttons show whether you can rate now or how long you must wait.

Both giving and taking honor require a written reason, with at least **3 visible characters** by default and at most **100 characters**. Reasons are rendered as inert plain text. Profile GUI buttons prompt for the reason in chat and open a confirmation chest showing the target, exact cost, and reason. Confirmation lasts **60 seconds**; command ratings also support `/status confirm`. Payment and the recorded rating share the same honor path, with compensation handling for failed writes.

The word filter replaces listed whole words with **`bobba`** in public chat and displayed reasons. Matching ignores case and accents, and both language lists apply regardless of the active language. Original reasons remain stored.

The profile chest includes paginated rating history, signed changes, dates, and reasons. Rater names are hidden until revealed for **100** economy units by default; the reveal is remembered for that viewer. Administrative `/status history` provides text history. Administrators can revoke a rating with `/status admin revoke <player> <id>`, use `/status admin revoke <player> last` for the latest revocable rating, or shift-click its history column and confirm. Admin history shows the exact revoke command. Revocation preserves the event and audit trail, removes its status contribution, and reverses any mental-state delta it applied within the scale bounds. It refunds no money and restores no rating allowance. Revoked entries are hidden from ordinary viewers and marked for administrators with revoke permission.

### Kills and sanctioned duels

An eligible open-world kill affects Psychosis independently of the status penalty. The default penalty is **−1 status**, limited to one penalty per killer/victim pair every **30 minutes** and **10 total automatic status loss per killer in 7 days**. Owners can exempt worlds from the status penalty. These penalty limits do not suppress the mental-state input.

Duels support one-on-one fights, multiple opponents, and teams using `vs`. All invited participants must accept; challenges expire after **60 seconds**. Kills within the sanctioned duel context change neither status nor Psychosis. Leaving forfeits. Disconnecting within **10 seconds** of combat damage is classified as combat logging, with immediate forfeiture, audit, and a configured broadcast or notification. Other disconnects allow **30 seconds** to reconnect before forfeiture.

### Mental state and private madness

One persistent scale runs from **Psychosis 100 → Neutral → Serenity 100**. Players are never mad and serene at once. Good actions gradually cure psychosis, then build serenity; bad actions drain serenity, then raise psychosis. Each action stops at Neutral rather than spilling into the opposite direction. Actions change the state by their configured amounts rather than resetting it outright, and elapsed time does not erase it. Accepted duels are excluded from gameplay inputs.

| Input | Default change | Rolling 24-hour cap |
| --- | --- | --- |
| Player kill | Drain 25 serenity or add 10 psychosis | None |
| Death from any cause | Drain 10 serenity or add 6 psychosis | None |
| Near-death: damage crosses down to at most 4 health points (two hearts), while alive | Drain 3 serenity or add 2 psychosis; re-arms above 4 health | None |
| Sleepless Overworld night, active for at least half its nominal length | Drain 2 serenity or add 2 psychosis | None |
| Clean day: 24 real hours since the last bad action or clean-day credit, including at least 30 active minutes | Cure 1 psychosis or gain 1 serenity | 1 credit |
| Sleep through a night | Cure 1 psychosis or gain 0.5 serenity | 2 nights |
| Fishing, breeding, feeding, planting, harvesting | Cure 0.02 psychosis or gain 0.02 serenity | 25 actions shared across all five |
| Positive / negative ratings received | Positive: cure/gain 2; negative: drain/add 2 | 3 received ratings shared across both signs |

Peaceful actions mean catching fish, breeding animals, feeding into love mode or baby growth, planting crops on farmland, and harvesting mature crops. Fishing requires recent non-fishing activity, so AFK farms earn nothing. Offline and idle/AFK time earn nothing; active time alone gives no credit. Qualifying activity includes player-driven movement and gameplay interactions, with a default **300-second** idle timeout; chat and passive transport do not qualify. Ratings beyond the mental-state cap still change status. Administrative adjustments and system penalties do not apply the rating input.

Psychosis levels use magnitude: **Low** above 0 and below 20, **Medium** from 20, **High** from 50, and **Extreme** from 80 to 100. Neutral triggers no episodes.

| Level | Eligible cosmetic effects with shipped defaults | Maximum effects started together |
| --- | --- | --- |
| Low | Particles, title/action-bar flashes, sourceless sounds, self-only fake join/leave messages, boss bars, private whispers and custom lines | 1 |
| Medium | Low catalogue plus creeper fuse sounds; chat corruption begins | 2 |
| High | Also private night sky, equivalent block appearances, sign text, hurt flashes without damage, victim ghosts, harmless hostile phantoms, and false death notices | 3 |
| Extreme | High catalogue, with more frequent episodes and longer bounded visuals | 4 |

Effects retain their individual level floors, cooldowns, session caps and quiet intervals; fewer play when fewer are eligible. Temporary appearances restore during cleanup. Phantom mobs have no real server entity, damage, collision or drops, and appear outside interaction reach; hostile phantoms require non-Peaceful difficulty. Victim ghosts use the player's eligible victim history; false death notices require another visible living player and rotate through configured lines. The advancement toast is configured but not delivered: Paper 26.3 cannot show one without granting a real advancement. There is no nausea, inventory deception, or player `/status effects` opt-out.

**Chat corruption and darkening** affect occasional messages from Medium/High/Extreme speakers at default rates of **10%/25%/40%**, with corruption extents of **20%/35%/50%**. All readers see the same altered body. Alternating messages remain intact, at least half the letters stay unchanged, and messages shorter than 6 letters are skipped. Only episode bodies darken; prefixes and hover remain intact. Social status does not drive corruption or madness effects.

### Serenity, chat, and language

**Serenity is the peaceful direction of the same mental state.** It brings private dawn, clean sounds, gentle particles, and friendly animal apparitions selected from turtles, foxes, armadillos and bees by default. Apparitions follow at a safe distance (default **6 blocks**) and cannot be harmed or interacted with. All are cosmetic packet-only visuals, with no companions, buffs, healing or rewards. Serenity episodes play one effect at a time.

Eligible nearby observers can hear the sounds and see particles and apparitions within **16 blocks** by default, respecting visibility and world boundaries. Dawn remains private. No serene title or chat corruption appears.

Chat carries the tier prefix and a name hover with status, tier, Confidence, one mental-state line, and how many distinct players rated them. If another plugin owns the chat renderer, SocialBlueprint leaves that renderer in control, so its chat presentation may not appear.

English and Spanish message files are bundled. Colors use Essentials-style `&` codes, including hex colors. Owners can edit behavior and messages live in-game. The GitHub updater stages verified releases for the next restart.

## Commands

`/pstatus` and `/reputation` alias `/status`. Permissions below are the default nodes; mappings are configurable. Honor and duel actions require a player. Console can inspect a named profile, use configuration/admin/import/update commands, and read named text history.

| Syntax | Action | Permission |
| --- | --- | --- |
| `/status` | Open your profile chest | `socialblueprint.show` |
| `/status <player>` | View another profile (text for console) | `socialblueprint.show.others` |
| `/status info <player>` | Alias for viewing another profile | `socialblueprint.show.others` |
| `/status psychosis [player]` | Show the mental state | `socialblueprint.show` for self; `socialblueprint.show.others` with a target |
| `/status give <player> <reason>` | Preview a positive rating; alias: `trust` | `socialblueprint.give` |
| `/status take <player> <reason>` | Preview a negative rating; aliases: `remove`, `distrust` | `socialblueprint.take` |
| `/status <player> + <reason>` | Legacy positive-rating syntax | `socialblueprint.give` |
| `/status <player> - <reason>` | Legacy negative-rating syntax | `socialblueprint.take` |
| `/status confirm` | Confirm your pending rating; rechecks its action permission | `socialblueprint.give` or `socialblueprint.take` |
| `/status history [player]` | Administrative text history; console must name a target | `socialblueprint.admin.adjust` or `socialblueprint.admin.revoke` (console is always allowed) |
| `/status duel <opponent> [opponent…]` | Challenge one or more opponents | `socialblueprint.duel` |
| `/status duel <ally…> vs <opponent…>` | Team challenge; sender joins the first side | `socialblueprint.duel` |
| `/status duel accept [challenger]` | Accept a challenge; alias: `/status accept [challenger]` | `socialblueprint.duel` |
| `/status duel deny [challenger]` | Decline a challenge; alias: `/status deny [challenger]` | `socialblueprint.duel` |
| `/status duel leave` | Forfeit; alias: `/status leave` | `socialblueprint.duel` |
| `/status admin give <player> [amount]` | Add status; amount defaults to 1 | `socialblueprint.admin.adjust` |
| `/status admin take <player> [amount]` | Subtract status; amount defaults to 1 | `socialblueprint.admin.adjust` |
| `/status admin reset <player>` | Reset status through a compensating event | `socialblueprint.admin.adjust` |
| `/status admin revoke <player> <id\|last>` | Revoke a rating by id or the latest revocable rating | `socialblueprint.admin.revoke` |
| `/status admin mind set <player> <value>` | Set a finite value from -100 (Psychosis) to +100 (Serenity) | `socialblueprint.admin.mind` |
| `/status admin mind reset <player>` | Reset one online or offline player to Neutral | `socialblueprint.admin.mind` |
| `/status admin mind reset-all [confirm]` | Reset all stored players; repeat with `confirm` within 30 seconds | `socialblueprint.admin.mind` |
| `/status admin features` | Open the live input/effect toggle chest in-game | `socialblueprint.admin.features` |
| `/status import [file]` | Import PlayerStatus; alias: `/status admin import [file]` | `socialblueprint.admin.import` |
| `/status config [get] <key>` | Read a supported behavior or message key | `socialblueprint.admin.config` |
| `/status config [set] <key> <value>` | Validate, save, and apply a supported key | `socialblueprint.admin.config` |
| `/status config reload` | Reload YAML files | `socialblueprint.admin.config` |
| `/status version` | Check running and available versions | `socialblueprint.version` |
| `/status update check` | Check releases without downloading | `socialblueprint.admin.update` |
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
| `socialblueprint.admin.mind` | Operators | Mental-state set/reset operations |
| `socialblueprint.admin.features` | Operators | Live feature toggle GUI |
| `socialblueprint.admin.revoke` | Operators | Rating revocation and revoked-history visibility |
| `socialblueprint.admin` | Operators | All administrative nodes |
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
| `psychosis.levels.low`, `.medium`, `.high`, `.extreme` | `0`, `20`, `50`, `80` | Mental-state level boundaries; Low starts above zero |
| `psychosis.inputs.<id>.*` | Enabled; amounts and caps above | Individual mental-state inputs |
| `honor.reason.min-length` | `3` | Minimum visible reason length |
| `chat-filter.enabled`, `.words.en`, `.words.es` | `true`, English/Spanish word lists | Chat and reason filtering |
| `psychosis.chat.<level>-rate`, `.<level>-extent` | Rates `10/25/40`, extents `20/35/50` | Corruption probability and amount |
| `psychosis.chat.<level>-colour`, `.min-letters` | `#AAAAAA/#666666/#303030`, `6` | Episode body color and minimum text length |
| `psychosis.serenity.ceiling`, `.idle-timeout-seconds` | `100`, `300` | Presentation bound and qualifying activity timeout |
| `duel.challenge-timeout`, `.attack-context-window` | `60s`, `30s` | Challenge expiry and recorded attack context |
| `duel.disconnect.combat-log-window`, `.reconnect-grace-period`, `.action` | `10s`, `30s`, `broadcast` | Disconnect classification and social response (`broadcast` or `notify`) |
| `effects.check-interval`, `.max-episode-ticks` | `1s`, `200` | Scheduler and bounded sound episodes |
| `effects.episodes.<level>.interval-ticks`, `.max-concurrent` | Low/Medium/High/Extreme: `4800/2400/1200/400`, `1/2/3/4` | Madness cadence and simultaneous effects |
| `effects.debug` | `false` | Log scheduler eligibility, delivery and skip reasons |
| `effects.quiet-interval.<medium\|high\|extreme>` | `2m/1m/20s` | Silence floors between madness episodes |
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
/status config peaceful.cap 25
/status config effects.debug true
/status config tiers.tier1.prefix &7[&a|&7]
/status config reload
```

Unique dot-separated key suffixes work too: `peaceful.cap` resolves to `psychosis.inputs.peaceful.cap`. Ambiguous suffixes list matching full keys; tab completion offers short unique forms.

`/status admin features` toggles inputs, madness effects, chat corruption and serenity effects through the same saved, validated live-edit path. Changes apply immediately and are audited; an effect already playing finishes and cleans up normally. Mind set/reset commands support console and offline players and are audited. Reset restarts the clean-day clock but preserves used 24-hour caps.

Live edits validate before applying and persist to disk. Unsupported keys and invalid values are rejected. Manual YAML edits take effect with `/status config reload`.

The [English](src/main/resources/messages_en.yml) and [Spanish](src/main/resources/messages_es.yml) files hold tier/metric names, command replies, chat hover text, GUI labels/prompts, updater messages, and effect text. Known message keys are editable through the same command in the active language, for example:

```text
/status config tiers.tier1 Affable
/status config effects.private-chat.custom-lines ['&8The walls remember.', '&7Something is watching.']
```

Operators can add custom hallucinations in `effects.private-chat.custom-lines` (empty by default). Other catalogue lists include `effects.private-chat.lines`, `effects.sign.lines`, `effects.screen-flash.lines`, `effects.advancement-toast.lines`, and `effects.boss-bar.lines`. Fake-connection and false-death templates live under `effects.fake-connection.join`, `.leave`, and `effects.false-death.lines`.

Custom/private-chat, toast, and boss-bar lines must be nonblank, validly colored, single-line, noninteractive text: no control characters, line separators, or click/hover/action directives. Private chat is limited by `effects.private-chat.max-visible-length` (default **160 visible characters**); toast and boss-bar lines are bounded at 160. Validation reserves 16 characters for `{player}` in these lists. Custom lines additionally reject English/Spanish server-notice, moderation, permission, economy, and reward wording and currency symbols, preventing imitation of authoritative notices. Load and live-edit errors identify the offending key/list entry. Edit both language files when providing text for both languages.

## Updating

1. Run `/status update check` (admin) or `/status version` to check the running version against GitHub releases.
2. Run `/status update` with administrative permission to download and stage a **strictly newer** release. Equal or older releases are not staged.
3. Restart the server after the success message.

`update.channel: stable` checks the latest stable release; `beta` also considers prereleases. Startup checks are enabled, but automatic downloading is off by default. Downloads require SHA-256 verification and JAR validation before staging. Missing or mismatched checksums reject the update.

The verified jar goes into Paper's update folder, normally `plugins/update/`, using the current plugin jar's filename when available. The running jar is left in place until restart. Network or release-check failures are reported without preventing startup.

Upgrading from **1.0** converts existing data automatically: eligible kills in the old window become Psychosis `min(100, 10 * kills)`; otherwise credited serenity keeps its old curve value, and other players start Neutral. Existing kill history is retained, and conversion records a mental-state event without inventing playtime. Back up the stopped server's data folder first.

## Building from source

Use **JDK 25** and the checked-in Maven wrapper:

```sh
./mvnw clean verify
```

On Windows:

```powershell
.\mvnw.cmd clean verify
```

The jar is `target/SocialBlueprint-2.0.jar`. On systems where in-process javac fails, use `./mvnw clean verify -Dmaven.compiler.fork=true`.

## Roadmap — not shipped

The spec's [Later releases](docs/spec.md#12-later-releases) lists these remaining items:

- Further Reputation Confidence decay and recalculation work; its displayed label stays unchanged.
- Vouching backed by a monetary guarantee.
- Trade warnings, CoreProtect, land-claim integrations, and a reputation event API.
- Further votes affecting mental state, still listed as blocked by the constitution and open to brigading; the capped received-honor input described above is approved and shipped.
- Advancement toast delivery if Paper later supports a grant-free toast.

These are plans, not v2.0 capabilities or release commitments.

## License

Released under the [MIT License](LICENSE).
