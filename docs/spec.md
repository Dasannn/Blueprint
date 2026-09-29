# Specification — SocialBlueprint

Subordinate to `docs/constitution.md`. Describes *what* the plugin does. How it
is built belongs in `ARCHITECTURE.md`.

Requirements are numbered `SB-nnn` so tasks and reviews can cite them.

## 1. Scope of the first release

Release 1 covers the core, duels, and the low-status ambient effects. The honor
economy, comments, GUI, Confidence, vouching and external integrations follow in
later releases, in the order of §11.

The first release must also **fix the baseline**: as imported, the plugin does
not survive a fresh enable (`docs/reference/baseline-audit.md` §1.1).

## 2. Social metrics

**SB-001.** The plugin stores three independent per-player metrics: social
status (signed integer), Reputation Confidence, Killing Psychosis. No metric is
derived from another.

**SB-002.** Social status is derived from stored reputation events, not held as
a standalone authoritative integer.

**SB-003.** Reputation Confidence is one of `Unknown`, `Low`, `Established`,
`High`, computed from the count of **distinct** players who have rated this
player, weighted by the age of those ratings. Repeated ratings from one actor do
not raise Confidence.

**SB-004.** Killing Psychosis measures PvP frequency over a rolling window. It
is never reduced by, and never reduces, social status.

**SB-005.** A player with no record reads as status `0`, Confidence `Unknown`,
Psychosis at its lowest level. Never as negative or suspect.

## 3. Status, tiers and prefixes

**SB-010.** Nine tiers, keeping the existing visual tokens and Spanish names:
`Criminal`, `Forajido`, `Delincuente`, `Temerario` (negative), `Particular`
(neutral), `Afable`, `Honorable`, `Insigne`, `Ilustre` (positive).

**SB-011.** Tier thresholds are signed and strictly ordered. The baseline's
positive `repRequired` values on negative tiers are a defect
(`config.yml:1-16`) and are corrected. The ladder is validated on load; an
invalid ladder is a startup error with a message naming the offending key, never
a silent fallback.

**SB-012.** A player at exactly `0` resolves to the neutral tier `Particular`.

**SB-013.** Prefixes are resolved per lookup from current configuration, not
cached once at enable. Editing the configuration in-game takes effect without a
restart.

**SB-014.** The plugin never overwrites a player's display name, list name or
custom name unconditionally. Coexistence with other prefix plugins is a
requirement, not an accident.

## 4. Chat

**SB-020.** A low-status player's own chat messages are rendered darker, on a
gradient from near-black (`#202020`) at the bottom to bright white at the top.
Absolute black is never used.

**SB-021.** Darkening never hides, truncates, delays or blocks a message.

**SB-022.** Hovering a player's name in chat shows a compact summary: status,
tier, Confidence, Psychosis, and the number of distinct players who contributed.

## 5. Duels

**SB-030.** Players can start a consensual duel, 1v1 or group. Both sides (or
all sides) must accept; an unaccepted challenge expires.

**SB-031.** A kill inside an active duel affects **neither** social status
**nor** Killing Psychosis. Consented combat is not evidence of anything.

**SB-032.** Outside a duel, a player kill raises Killing Psychosis and **never**
changes social status. This reverses the baseline behaviour at
`EventManager.java:24-29`.

**SB-033.** Duel state survives a player disconnect long enough to distinguish a
combat log from a normal quit; the handling is configurable.

## 6. Low-status ambient effects

Governed by `docs/decisions/0002-low-status-effects-are-private-and-cosmetic.md`.

**SB-040.** Below a configurable status threshold, a player may receive: Speed
III silverfish that despawn on a timer, near-black short chat lines, creeper
fuse sounds, and fake join/leave announcements.

**SB-041.** Every effect is visible or audible **only** to the affected player.

**SB-042.** The silverfish deal no damage, take no damage, target nothing, drop
nothing, are not persistent, and are removed on timer, on quit, on world change
and on server stop.

**SB-043.** Each effect has an independent cooldown and per-session cap, both
configurable.

**SB-044.** A player can disable the effects for themselves. Doing so changes no
metric and hides nothing from other players.

## 7. Honor economy

Governed by `docs/decisions/0001-honor-cost-is-a-fixed-yaml-amount.md`.

**SB-050.** Giving or removing honor charges the actor a fixed amount configured
in YAML, multiplied by a progressive factor based on how many ratings that actor
has issued inside a rolling window.

**SB-051.** Vault is required. Without an economy provider the plugin does not
enable, and says why.

**SB-052.** The exact cost is shown to the actor and confirmed before any money
moves or any event is written.

**SB-053.** A cooldown applies per actor-target pair.

**SB-054.** An actor may hold at most three positive and three negative honors
against one target inside the window; the two counts are independent.

**SB-055.** Removing honor means withdrawing an honor the actor previously gave.
Giving negative honor is a distinct action. Both are charged.

**SB-056.** Negative honor requires a written reason. Positive honor may carry
one.

**SB-057.** The charge and the reputation event commit together. A failed charge
writes no event; a failed event write refunds.

## 8. Identity, permissions and configuration

**SB-060.** Players are keyed by **UUID** everywhere. The baseline's name-keyed
vote lists (`main.java:93-102`) are a defect: a name change evades them.

**SB-061.** Every permission node is declared in the manifest and configurable.
Checks go through the Bukkit permission API so LuckPerms grants apply unchanged.

**SB-062.** Every player-visible string is configurable from YAML and in-game,
including the plugin's own chat prefix.

**SB-063.** Colours accept Essentials-style `&` codes, including hex, in every
configurable string.

**SB-064.** Administrative actions — manual adjustments, hiding comments,
reverting events, bypassing cooldowns — require explicit permission and write an
audit record.

**SB-065.** Commands work from the console wherever meaningful, and accept
offline players by name or UUID. The baseline crashes the console on every
command (`Commands.java:21-22`).

## 9. Commands

Root `/status`, with `/pstatus` and `/reputation` as aliases. `/utils` is
removed.

| Command | Purpose |
|---|---|
| `/status [player]` | Show a social profile |
| `/status trust <player>` | Give positive honor |
| `/status distrust <player>` | Give negative honor, with a reason |
| `/status revoke <player>` | Withdraw an honor previously given |
| `/status psychosis [player]` | Show Killing Psychosis detail |
| `/status duel <player>` / `accept` / `deny` / `leave` | Duels |
| `/status effects` | Toggle one's own ambient effects |
| `/status config <key> [value]` | Read or edit configuration in-game |
| `/status admin ...` | Audited administrative operations |
| `/status version` | Show the running version and whether it is current |
| `/status update` | Download the latest release |

## 10. Updates from GitHub

**SB-070.** `/status version` reports the running version and compares it
against the latest release published on the plugin's GitHub repository.

**SB-071.** `/status update` downloads that release's jar and writes it into the
server's `plugins/update/` directory, so the server applies it on the next
restart. The running jar is never replaced in place — swapping a loaded jar
breaks the class loader.

**SB-072.** The update command reports clearly that a restart is required, and
does not restart the server itself.

**SB-073.** The version check runs off the main thread and fails quietly: no
network access, a rate limit, or an unreachable GitHub must never delay a tick
or prevent startup.

**SB-074.** The download verifies the release asset's checksum before writing
it. A mismatch aborts and leaves `plugins/update/` untouched.

**SB-075.** Automatic checking on startup is configurable and defaults to on;
automatic *downloading* defaults to off.

**SB-076.** The repository and release channel are configurable, so a fork or a
private build can be pointed somewhere else.

## 11. Later releases

In order. Each becomes its own specification section when it is reached.

1. Public comments with mandatory reasons and the Bobba profanity filter.
2. Profile GUI with cost preview and explicit confirmation.
3. Reputation Confidence as a displayed metric, with decay and recalculation.
4. Vouching with a monetary guarantee.
5. Integrations: trade warnings, CoreProtect, land claims, reputation event API.

## 12. Removed from the baseline

Deliberate deletions, so no agent restores them as "missing functionality".

- **`/pstatus evaluate` and all `eco give` / `eco take` dispatch**
  (`main.java:397-499`). Paid players money for their reputation, violating
  constitution §2.1, and had no idempotency — it could be run repeatedly to mint
  currency.
- **Item rewards on death by reputation** (`config.yml:44-53`). Same violation.
- **Automatic status loss on any player kill** (`EventManager.java:24-29`).
  Replaced by SB-032.
- **`PrefixManager`** — entirely commented out, and wrongly extends
  `JavaPlugin`.
- **`/utils`** — declared and registered with no handler.

## 13. Acceptance criteria for release 1

- [ ] A fresh install enables with no configuration present, and with a
      configuration file from the old plugin.
- [ ] A player at minimum status can chat, build, trade and move normally.
- [ ] The nine tiers resolve correctly across the full status range, including
      exactly `0` and both extremes.
- [ ] Editing a prefix, a colour or a message in-game takes effect with no
      restart.
- [ ] Status, Confidence and Psychosis are stored and displayed as three
      separate values.
- [ ] A duel kill changes neither status nor Psychosis; a non-duel kill changes
      only Psychosis.
- [ ] Ambient effects reach only the affected player, respect their cooldowns,
      and leave no entity behind after quit or restart.
- [ ] A player can disable ambient effects for themselves.
- [ ] Console can run every command without an exception.
- [ ] A name change does not detach a player from their record.
- [ ] No file I/O happens on the main thread.
- [ ] `/status version` reports correctly with GitHub reachable and with GitHub
      unreachable.
- [ ] `/status update` places a checksum-verified jar in `plugins/update/` and
      the server picks it up on restart.
- [ ] Every administrative action leaves an audit record.
- [ ] Every value named in this document is configurable without recompiling.
