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

**SB-006.** A reputation event's contribution to social status **decays with
its age**, on a curve configured in YAML. Nothing is deleted: the event stays in
the history at full fidelity and only its weight in the current status falls.
This is what makes constitution §2.6 real — a player who stops behaving badly
recovers as their old events fade, without needing anyone to forgive them.

This decay is **separate from Confidence's** age weighting (SB-003) and has its
own configuration. Confidence answers "how much evidence is there"; this answers
"how much does an old opinion still count". Moving one must not move the other.

## 3. Status, tiers and prefixes

**SB-010.** Nine tiers, keeping the existing visual tokens and Spanish names:
`Criminal`, `Forajido`, `Delincuente`, `Temerario` (negative), `Particular`
(neutral), `Afable`, `Honorable`, `Insigne`, `Ilustre` (positive).

**SB-011.** Tier thresholds are signed and strictly ordered. The baseline's
positive `repRequired` values on negative tiers are a defect
(`config.yml:1-16`) and are corrected. The ladder is validated on load; an
invalid ladder is a startup error with a message naming the offending key, never
a silent fallback.

**SB-011a.** The ladder is **symmetric**: the negative thresholds mirror the
positive ones at `-5`, `-15`, `-30`, `-50`. The baseline's inherited values
(`-1`, `-10`, `-20`, `-30`) gave the first negative tier a range of exactly one
point, so a single negative rating from neutral dropped a player two tiers while
the positive side allowed five points of movement before the first promotion.
Falling must not be cheaper than rising.

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

**SB-032.** Outside a duel, a player kill raises Killing Psychosis **and**
lowers social status. Governed by
`docs/decisions/0004-a-non-duel-kill-costs-status.md`. The status change is a
**system-authored reputation event**: actor `SYSTEM`, no cost, a message-key
reason, a delta configured in YAML (default `-1`). It is stored like every other
event (constitution §2.5), decays like every other event (SB-006), and is
visible in the history GUI (§11).

The two metrics stay separate (constitution §2.3, SB-004): Psychosis still never
reduces status and status never reduces Psychosis. It is the *kill* that feeds
both, each by its own rule.

**SB-034.** The automatic status penalty never counts towards Reputation
Confidence. `SYSTEM` is not a distinct player under SB-003.

**SB-035.** Repeat kills of the same victim by the same killer inside a
configurable cooldown apply the penalty **once**. A configurable per-window cap
bounds how much status one player can lose automatically, so a single evening of
PvP cannot bottom out a record that peers must otherwise vote down.

**SB-036.** The penalty is skipped entirely when the kill is ambiguous or
consented (constitution §2.7): inside a duel (SB-031), by a killer the plugin
cannot identify, and in any world listed as exempt in YAML. Setting the delta to
`0` disables the whole behaviour.

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

**SB-054.** Inside the rolling window, one actor may give at most three positive
and three negative honors to one target. The two counts are independent, and the
cap is per actor-target pair: every other player has their own allowance against
the same target. The window expiring restores the allowance.

**SB-055.** There are exactly two honor actions available to players: **giving**
honor (positive) and **removing** honor (negative). Removing honor lowers the
target's status directly; it is not the withdrawal of something the actor gave
earlier. A player cannot undo an honor they have issued — only an administrator
can, under SB-058.

**SB-056.** Negative honor requires a written reason. Positive honor may carry
one.

**SB-057.** The charge and the reputation event commit together. A failed charge
writes no event; a failed event write refunds.

**SB-058.** Administrators can give honor, remove honor and reset a player's
honor to neutral, through dedicated commands. These operations are free, ignore
the cooldown and the cap, and exist to correct mistakes and reverse coordinated
abuse. Every one of them writes an audit record naming the administrator, the
target, the change and the time. A reset does not erase the underlying
reputation events; it writes a compensating event, so the history stays
reconstructible under constitution §2.5.

## 8. Identity, permissions and configuration

**SB-060.** Players are keyed by **UUID** everywhere. The baseline's name-keyed
vote lists (`main.java:93-102`) are a defect: a name change evades them.

**SB-061.** Every permission node is declared in the manifest and configurable.
Checks go through the Bukkit permission API so LuckPerms grants apply unchanged.

**SB-062.** Every player-visible string is configurable from YAML and in-game,
including the plugin's own chat prefix.

**SB-066.** One plugin ships both Spanish and English. The active language is a
single key in `config.yml`; there is no separate build, no separate download.

**SB-067.** Each language is a file — `messages_es.yml`, `messages_en.yml` —
with identical keys. Both ship inside the jar and are written to the plugin's
data folder on first run so a server owner can edit either.

**SB-068.** A key missing from the selected language falls back to the other
language rather than showing the raw key, and logs a warning naming the key
once. A missing translation must never reach a player as `messages.honor.cost`.

**SB-069.** No player-visible string is ever written in Java source. Every one
goes through the message layer, so adding a third language later is a new file
and nothing else.

**SB-070i.** The nine tier names are translated too. A tier's **prefix token**
(`&7[&a||&7]`) and its **threshold** stay in `config.yml`, because they are
structure; its **display name** lives in the language files, keyed by tier, so
an English player reads `Honourable` where a Spanish player reads `Honorable`.
Editing a name in either language file is the supported way to rename a tier.

**SB-063.** Colours accept Essentials-style `&` codes, including hex, in every
configurable string.

**SB-090.** **Every sound the plugin plays is configurable.** Each sound is a
named slot in `config.yml` carrying the Minecraft sound key, volume, pitch and
category — for example `entity.creeper.primed, 1.0, 0.5, HOSTILE`. No sound key
is written in Java source. An empty or absent slot plays nothing, which is how
an owner silences one. An unrecognised key logs a warning naming the slot once
and plays nothing; it never throws and never blocks the action the sound
accompanied.

**SB-091.** Sound slots are addressed by name, so an owner can retarget an
existing slot to a different Minecraft sound, and new slots added by later
features need no code change beyond playing them. Sounds obey SB-041 where they
belong to a private ambient effect: they reach only the affected player.

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
| `/status give <player>` | Give honor |
| `/status take <player>` | Remove honor, with a reason |
| `/status psychosis [player]` | Show Killing Psychosis detail |
| `/status duel <player>` / `accept` / `deny` / `leave` | Duels |
| `/status effects` | Toggle one's own ambient effects |
| `/status config <key> [value]` | Read or edit configuration in-game |
| `/status admin give <player> [amount]` | Add honor, free, no cooldown, audited |
| `/status admin take <player> [amount]` | Remove honor, free, no cooldown, audited |
| `/status admin reset <player>` | Return a player to neutral, audited |
| `/status admin ...` | Other audited administrative operations |
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

## 11. Rating history and anonymity

**SB-080.** A player's rating history is browsable in a double chest GUI,
opened by `/status [player]`. The top row shows the subject: their head, a dye
whose colour follows their tier, and a green and a red banner for giving and
removing honor. Below it, one column per rating: the rater's head, a paper
holding their written reason, and a green or red banner for its direction.
Banners at the edges page through the history.

**SB-081.** The GUI is a **view**. It opens from cached data or a completed
asynchronous load, never a blocking read, and a rating made through it goes
through the same honor path as the command — same cost, same cooldown, same
cap, same audit. A second surface must not become a second set of rules.

**SB-082.** A rater's **identity is hidden by default**: the history shows their
head and their reason, not their name. Revealing one name costs a configurable
amount, charged through Vault, and the reveal is remembered for that viewer.
The reason text is always visible; only the name is paid for.

**SB-083.** A rater's comment is length-bounded, stored as written, and rendered
as plain text. It never carries colour codes, formatting or click actions,
whatever the rater typed: an item name is not a place where one player styles
another player's screen.

**SB-084.** Hiding a name changes nothing about accountability behind the
scenes. The cap of SB-054 is still counted per actor-target pair, an
administrator still sees who rated whom, and the audit trail is unaffected.


## 12. Later releases

In order. Each becomes its own specification section when it is reached.

1. Public comments with mandatory reasons and the Bobba profanity filter.
2. Profile GUI with cost preview and explicit confirmation.
3. Reputation Confidence as a displayed metric, with decay and recalculation.
4. Vouching with a monetary guarantee.
5. Integrations: trade warnings, CoreProtect, land claims, reputation event API.

## 13. Removed from the baseline

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

## 14. Acceptance criteria for release 1

- [ ] A fresh install enables with no configuration present, and with a
      configuration file from the old plugin.
- [ ] A player at minimum status can chat, build, trade and move normally.
- [ ] The nine tiers resolve correctly across the full status range, including
      exactly `0` and both extremes.
- [ ] Editing a prefix, a colour or a message in-game takes effect with no
      restart.
- [ ] Status, Confidence and Psychosis are stored and displayed as three
      separate values.
- [ ] A duel kill changes neither status nor Psychosis; a non-duel kill raises
      Psychosis and lowers status by the configured delta, once per pair
      cooldown, bounded by the per-window cap, and never touches Confidence.
- [ ] Every sound the plugin plays can be retuned, silenced or replaced from
      `config.yml` without recompiling, and an unknown sound key logs a warning
      instead of throwing.
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
- [ ] Changing `language:` from `es` to `en` changes every player-visible
      string, with no other edit and no separate build.
