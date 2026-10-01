# Specification — SocialBlueprint

Subordinate to `docs/constitution.md`. Describes *what* the plugin does. How it
is built belongs in `ARCHITECTURE.md`.

Requirements are numbered `SB-nnn` so tasks and reviews can cite them.

## 1. Scope of the first release

Release 1 covers the core, duels, and the Psychosis ambient effects. The honor
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

**SB-093.** The Psychosis rolling window defaults to **72 hours**, replacing
the previous 24-hour default, and remains configurable in YAML and in-game.
Eligible kills raise it immediately; their contribution expires only when they
leave the window. It rises quickly and falls slowly, so stopping the killing
allows recovery without making its consequences disappear after one evening.
This changes neither the duel exemption (SB-031) nor the independent status
penalty (SB-032).

**SB-005.** A player with no record reads as status `0`, Confidence `Unknown`,
Psychosis at its lowest level. Never as negative or suspect.

**SB-087.** SB-005 answers for a **player**. A name that belongs to nobody
is not a player with no record: `/status asdkjhasd` must say the player was
not found, not invent a neutral profile. In offline mode any string resolves
to some uuid, so resolution must require evidence that the name is real --
a stored profile, a player online now, or one the server has seen before --
and otherwise refuse. Inventing a profile for a typo teaches an operator
to trust an answer that means nothing.

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

**SB-020.** Superseded by SB-094 and SB-095. The former status-based chat
colour gradient attached the speaker's failing voice to the wrong metric.

**SB-021.** Superseded by SB-095. The prohibition on silencing a player remains,
but now applies to partial text corruption rather than darkening.

**SB-094.** Status keeps the tier ladder and the coloured chat **prefix**.
It no longer colours the message body. Status tells others how the community
sees the speaker; it does not describe the speaker's state of mind.

**SB-095.** Psychosis can corrupt the speaker's message by substituting letters,
scrambling words or mangling characters. At medium Psychosis this happens
occasionally, at high more often, and at extreme frequently, with intact
messages between episodes at every level (SB-097). The lowest level leaves
messages intact. Every reader receives the **same corrupted text**, including
the speaker: this is the speaker's voice failing, not a reader's hallucination.
Governed by `docs/decisions/0006-psychosis-corrupts-the-speakers-chat.md`.

Corruption affects only part of a message and leaves it readable enough to
communicate. It never renders the whole line illegible, hides, blocks, delays or
truncates it: a fully unreadable line would mute the player, turning a cosmetic
effect into exclusion. If a short message cannot be partially corrupted while
remaining readable, it stays intact. Prefixes and the name hover are untouched.
The frequency and extent are configurable, but no setting may remove these
guards. Rater-supplied text is still stored as written and rendered as plain
text under SB-083, never parsed for colour, formatting or click actions; chat
corruption gives no permission to reinterpret a rating or its reason.

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

## 6. Psychosis ambient effects

Governed by `docs/decisions/0005-psychosis-drives-private-ambient-effects.md`,
which supersedes decision 0002. These hallucinations are private; the speaker's
chat corruption is separately governed by SB-095.

**SB-040.** Superseded by SB-096 through SB-099. The status threshold is
removed, and the former timed Speed III silverfish become momentary phantoms.

**SB-096.** Killing Psychosis alone triggers ambient effects: phantom mobs,
short private chat lines, configurable layered sounds (including the creeper
fuse), fake connection messages and the catalogue of SB-102 through SB-115.
The lowest Psychosis level triggers none.
Status is not an eligibility check or a frequency input: a low-status player
with no Psychosis hears no whispers, while a high-status player with high
Psychosis can experience them.

**SB-097.** Effects are episodes, never a permanent state. Medium Psychosis
produces occasional episodes, high produces them more often, and extreme
produces frequent episodes **with silence between them**. Frequency and limits
are configurable, but every level must leave a nonzero quiet interval between
episodes, including any delayed sound layers. Cooldowns and session caps
(SB-043) still bound delivery. Chat corruption follows the same gradation and
must leave intact messages between episodes (SB-095); raising Psychosis cannot
make either presentation constant. Unease must leave room to play and speak.

**SB-098.** Fake join and leave messages name the affected player themselves:
"X joined the game" while X is already standing there. Only X receives them;
they are not real connection events and are never broadcast or logged as such.
The contradiction is about the player's own presence, not an invented visitor.

**SB-099.** From **High**, phantom mobs appear and vanish at once, as a momentary glimpse,
not a moving mob that remains for a timed encounter. They are private,
packet-only fakes: no server-side mob exists to cause collision or leak to
bystanders. They never deal or take damage, push, target, drop loot or XP, or
persist. Their managed lifecycle removes every fake on disappearance, quit,
world change and server stop, so an interrupted episode leaves nothing behind
after relog or restart. Their independent cooldown and session cap obey
SB-043/SB-097, with silence after disappearance; they change no status,
Confidence or money.

**SB-041.** Every ambient hallucination in this section is visible or audible
**only** to the affected player. SB-095 separately governs the speaker's chat,
whose corrupted text is shared by all readers.

**SB-042.** Superseded by SB-099. Harmlessness and cleanup remain required;
the timed silverfish encounter is replaced by an immediate glimpse.

**SB-043.** Each effect has an independent cooldown and per-session cap, both
configurable.

**SB-044.** Superseded by SB-101. The ambient opt-out is removed.

**SB-100.** Psychosis causes cosmetic effects, with the narrowly bounded nausea
exception of SB-112; it still neither derives from
nor alters status, as required by constitution §2.3 and SB-001/SB-004. Ambient
effects and chat corruption cannot change status, Reputation Confidence or
money, nor grant or remove a mechanical advantage except for that brief nausea.
A kill may feed Psychosis
and the separate status event of SB-032, but an effect never writes either
metric. SB-101 removes the former ambient opt-out; it does not change the text
shared under SB-095.

**SB-101.** There is no opt-out. `/status effects` existed because the
effects were something low status did **to** a player, and a player could
refuse them. Psychosis is earned by killing, so switching the effects off
would be switching off a consequence the player chose to incur, and the
mechanic would be decorative. The command and its stored flag are removed.
The effects stay private and incapable of touching status, Confidence or money
(SB-100). They are cosmetic except for the short, rare nausea explicitly
bounded by SB-112.

**SB-102.** From **High**, the player sees a private night or storm for a few
seconds, then their previous time and weather presentation returns. Use
`setPlayerTime` and `setPlayerWeather`; neither changes the world. No other
player sees this sky. Restore the prior override, or normal world tracking if
there was none, without overwriting a newer change by another plugin. It leaves
no override after quit, world change, relog or restart. Its duration, independent
cooldown and session cap obey SB-043/SB-097, with silence after restoration.
It changes no status, Confidence or money.

**SB-103.** From **Medium**, private particles briefly appear around or beneath
the player. No other player sees them; they are visual packets, with no entity,
collision or world change, and leave no trace after relog or restart. Their
bounded count, radius and duration, independent cooldown and session cap obey
SB-043/SB-097; the quiet interval begins after the last particle. They change
no status, Confidence or money.

**SB-104.** From **Medium**, a short line flashes across the player's screen as
a title or action bar, then goes. No other player sees it. It clears at the end
of the bounded display time and leaves no overlay after relog or restart.
It must not erase a newer title or action bar from another feature. Fade times
count towards the episode: its independent cooldown and session cap obey
SB-043/SB-097, leaving silence after the full display. It changes no status,
Confidence or money; its text comes from the message files.

**SB-105.** From **Medium**, the player hears sounds with no real source:
footsteps behind them, a door, an anvil, a bow or cave ambience. No other player
hears them and no door, anvil, arrow or entity is created or operated. Reuse the
named layered sound slots of SB-090 through SB-092, including their tick delays;
there is no parallel sequence mechanism. The independent cooldown and session
cap obey SB-043/SB-097, and silence starts after the final layer has finished,
not merely after it starts. Pending layers are cancelled on quit, world change
and stop; relog or restart resumes nothing. They change no status, Confidence
or money.

**SB-106.** From **High**, a nearby block appears to change, or a sign appears
with a short hallucination line, then the true block returns. Only the affected
player receives `sendBlockChange` and, for a sign, `sendSignChange`; no other
player sees either. The world and its sign data are never modified. The fake
does not replace a block the player is using, standing on or holding, and must
not create a client-side obstruction to movement. Restore the **current** true
block and sign data, not a stale snapshot; cancel pending fakes on quit, world
change and stop. Relog or restart shows the true block and leaves no trace.
The bounded duration and range, independent cooldown and session cap obey
SB-043/SB-097, leaving silence after restoration. Neither variant changes
status, Confidence or money; sign text lives in the message files.

**SB-107.** From **High**, the player sees a hurt flash and hears a damage sound
without being hurt. No other player sees or hears it. No damage event is
generated, no health or absorption is lost, and no knockback, velocity change,
invulnerability change or combat state is applied. The sound reuses an SB-092
slot. The momentary flash and final sound layer end before the SB-097 quiet
interval; an independent cooldown and session cap apply under SB-043. Pending
delivery is cancelled on quit or stop, leaving no trace after relog or restart.
It changes no status, Confidence or money.

**SB-108.** From **High**, the ghost of someone the player killed stands still
for a moment, then vanishes. Its displayed victim name comes from a real victim
in **that killer's own** eligible non-duel `psychosis_event` rows, resolved by
UUID to a known name. Read the existing history; do not invent a victim or
write a new event. If no such victim has a resolvable name, skip the effect.
It is a packet-only fake player entity with a distinct ghost appearance and a
message-file label identifying it as a ghost. It never copies a real player's
skin, account UUID, tab-list identity or ordinary nameplate in a way that could
be mistaken for that player actually being there. It appears and vanishes,
never moves, acts, speaks, collides, damages or responds to interaction, and
never appears to another player. Reuse managed-fake cleanup on disappearance,
quit, world change and stop; relog or restart leaves no entity or identity
behind. Its short duration, independent cooldown and session cap obey
SB-043/SB-097, leaving silence after removal. It changes no status, Confidence
or money.

**SB-109.** From **Medium**, the player sees a false advancement toast with a
hallucination line, then it disappears. No other player sees it. It grants no
real advancement, criterion, reward or statistic and writes nothing to the
player's advancement history. Any temporary client presentation is removed on
completion, quit or stop; relog or restart leaves no trace. Its bounded display
time, independent cooldown and session cap obey SB-043/SB-097, with silence
after the toast ends. It changes no status, Confidence or money; the text lives
in the message files.

**SB-110.** From **Medium**, a boss bar bearing a hallucination line appears for
a moment, then disappears. No other player sees it, no boss exists, and its
displayed progress represents no real health or objective. Only this effect's
bar is removed, on expiry, quit, world change or stop; it leaves no bar after
relog or restart. Its bounded duration, independent cooldown and session cap
obey SB-043/SB-097, leaving silence after removal. It changes no status,
Confidence or money; the text lives in the message files.

**SB-111.** From **High**, the player reads a false death message naming another
player who is visibly standing there alive. Select only an actual nearby living
player visible to the recipient at delivery; skip when none qualifies. No other
player receives it, and the named player remains alive and untouched. It is a
private hallucination, never a broadcast, death event, death-log entry, kill
credit, inventory drop or respawn. Like private chat lines it may remain in that
client session's scrollback, but is never stored or replayed: relog or restart
leaves no trace in game state. Its single-line delivery, independent cooldown
and session cap obey SB-043/SB-097, leaving silence between messages. It changes
no status, Confidence or money; its template lives in the message files.

**SB-112.** At **Extreme only**, brief nausea distorts the afflicted player's
view. No other player sees the distortion or receives the effect. This is
**not cosmetic: it impairs play**. It is justified as a bounded consequence the
player chose to earn by killing, not as a penalty for low social status. It is
the sole exception to SB-100's cosmetic-only rule: at most **40 ticks**, at
most **twice per login session**, with at least **6,000 ticks** between uses.
Configuration may shorten it or make it rarer, never exceed these ceilings or
lower that cooldown. It never occurs at lower levels, stacks, refreshes an
active nausea effect, or overwrites nausea from another source; skip if nausea
is already present. Remove only this plugin's application at expiry, quit,
world change, level dropping below Extreme or stop, preserving unrelated
effects. Relog or restart leaves no nausea behind. Reuse SB-043's independent
cooldown and session counter; SB-097 still requires silence after it ends,
including when another effect's layers are pending. It changes no status,
Confidence or money, and causes no damage, knockback or inventory change.

**SB-113.** **Rejected: inventory shuffling and any hallucination that changes
what the client believes it is holding.** There is no eligible level, cadence
or configuration switch for it. It risks genuine desynchronisation and item
loss: a cosmetic effect that can destroy property is not cosmetic. No player
receives such an effect, no state needs repair after relog or restart, and no
status, Confidence or money is touched. This is a prohibition, not a deferred
catalogue entry.

**SB-114.** Fake connection messages (SB-098) are available from **Medium**.
Their complete text and colour are configurable in the message files under
SB-062/SB-063, never fixed in code; the subject remains the recipient themselves.
Only that player sees them. They are single-line episodes bounded by an
independent cooldown and session cap (SB-043), with silence under SB-097.
They produce no actual connection event, broadcast or event log, are never
stored or replayed, and leave no game-state trace after relog or restart.
They change no status, Confidence or money.

**SB-115.** From **Medium**, built-in private chat lines and the operator's own
hallucination lines may be shown to the afflicted player. No other player sees
them. The custom list lives in each language's message file like all other
player-visible text, never in `config.yml`; an empty list adds nothing. Each
entry is one line, at most **160 visible Unicode code points** after colour
parsing and substitution, with a configurable lower limit. Reject line breaks,
click actions and impersonation of a real system message that would mislead
about a server event, including connection, death, advancement, moderation,
permission or economy events. The specifically approved fakes of SB-098,
SB-109 and SB-111 are separate built-in templates, not permission for custom
lines to impersonate server notices. Operators may use SB-063 colours; text
never becomes a command or interactive component. Invalid entries are rejected
on load or edit with the message key and list index named. These lines reuse
the existing private-chat delivery and share its independent cooldown and
session cap, rather than adding a second cadence. SB-097 leaves silence between
lines even at Extreme. Session scrollback aside, no line is stored or replayed
and relog or restart leaves no game-state trace. They change no status,
Confidence or money.

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

**SB-092.** A slot may hold **several layers**, each with its own key, volume,
pitch, category and a delay in ticks. They play in order from one trigger, so an
owner can build a chord (every layer at delay `0`), a sequence, or a quiet
texture under a main sound, without touching code. The single-mapping form of
SB-090 stays valid and means one layer at delay `0`.

A delay is scheduled on the server tick, so it is precise to 50 ms and no
finer. A key from a resource pack is played like any other: the plugin sends
the key it was given, and a client without that sound hears nothing.

**SB-064.** Administrative actions — manual adjustments, hiding comments,
reverting events, bypassing cooldowns — require explicit permission and write an
audit record.

**SB-065.** Commands work from the console wherever meaningful, and accept
offline players by name or UUID. The baseline crashes the console on every
command (`Commands.java:21-22`).

**SB-116.** Catalogue configuration extends the existing typed, validated,
live-editable snapshots and message layer; it does not introduce another
effects engine. In `config.yml`, every effect under `effects.<id>` has
`enabled`, `minimum-level`, `cooldown-ticks` and `session-cap`. The ids are
`sky`, `particles`, `screen-flash`, `source-less-sounds`, `block-change`,
`sign`, `hurt-flash`, `victim-ghost`, `advancement-toast`, `boss-bar`,
`false-death`, `nausea`, `fake-connection`, `private-chat` and `phantom-mob`.
Reuse existing cooldown and cap settings when adopting these names on upgrade;
preserve owner overrides. Caps count deliveries per login session and are not
reset by reload or changing level. A skipped effect consumes no delivery.
Minimum levels may be raised, never lowered below their catalogue floor:
Medium for the mild effects, High for sky, block/sign, hurt flash, victim ghost,
false death and the instant phantom of SB-099, Extreme for nausea.

`effects.episodes.<medium|high|extreme>.interval-ticks` and
`effects.episodes.quiet-ticks` bound the existing scheduler. Quiet ticks must
be positive. An episode ends only after every visual has cleared, every
restoration has completed and every delayed sound has finished; no next
episode starts before the quiet interval ends. Increasing level cannot bypass
an effect's cooldown or cap. Pending delivery is cancelled on quit, world
change and stop; temporary state is restored on expiry and these same cleanup
paths. Nothing is persisted to resume an episode after relog or restart.
Episode intervals are positive and strictly decrease from Medium to High to
Extreme; the quiet interval remains positive at all three levels. Delivery
still depends on available effects whose own cooldowns and caps permit it.

Additional behaviour keys, relative to `effects.<id>`, are:

| Effect / clause | Keys and bounds |
|---|---|
| Sky / SB-102 | `mode` (`night` or `storm`), `duration-ticks` |
| Particles / SB-103 | `type`, `placement` (`around` or `beneath`), `count`, `radius-blocks`, `duration-ticks`; count and radius must be positive and finite |
| Screen flash / SB-104 | `channel` (`title` or `action-bar`), `fade-in-ticks`, `duration-ticks`, `fade-out-ticks`; fades are nonnegative and included in total duration |
| Source-less sounds / SB-105 | `sound-slot`, `offset.forward-blocks`, `offset.right-blocks`, `offset.up-blocks`, `playback-ticks`; finite recipient-relative offsets allow footsteps behind; playback ticks bound the audible tail after the last delayed layer |
| Block change and sign / SB-106 | Each has `block-data`, `range-blocks`, `duration-ticks`; range is positive and finite, block data must support the selected presentation, and sign text is at most four lines of 80 visible code points each |
| Hurt flash / SB-107 | `sound-slot`, `playback-ticks`; flash is momentary, playback includes the final sound tail |
| Victim ghost / SB-108 | `range-blocks`, `duration-ticks`; positive finite range, distinct ghost appearance, no real-skin option |
| Advancement toast / SB-109 | `icon`, `duration-ticks`; icon is visual only, not an item grant |
| Boss bar / SB-110 | `colour`, `style`, `progress`, `duration-ticks`; progress lies in `[0, 1]` |
| False death / SB-111 | `range-blocks`; positive finite range and a living, visible nearby subject required |
| Nausea / SB-112 | `duration-ticks` from 1 to 40, `cooldown-ticks` at least 6,000, `session-cap` from 0 to 2; no stacking or amplifier setting |
| Custom/private chat / SB-115 | `max-visible-length` from 1 to 160; no custom-line list here |

Cosmetic `duration-ticks` values are positive and at most 100 ticks (five
seconds), including title fades. Sound episodes are finite: layer delays plus
`playback-ticks` total at most 100 ticks. Independent cooldowns are positive;
session caps are nonnegative, with `0` delivering nothing. Particle count and
radius are bounded by the configured values, never an unbounded stream. Invalid
keys or values fail validation naming their path; neither reload nor in-game
editing may bypass these guards. `sound-slot` refers to `sounds.<slot>` under
SB-090 through SB-092, with existing key, volume, pitch, category and tick-delay
fields; it does not contain another sequence format.

Both `messages_es.yml` and `messages_en.yml` carry these catalogue keys, with
SB-068 fallback and SB-063 colour handling:

| Message key | Purpose / clause |
|---|---|
| `effects.fake-connection.join`, `effects.fake-connection.leave` | Complete coloured templates with recipient `{player}` / SB-114 |
| `effects.private-chat.lines` | Existing built-in private lines / SB-115 |
| `effects.private-chat.custom-lines` | Operator-supplied list, independently editable per language / SB-115 |
| `effects.screen-flash.lines` | Title/action-bar lines / SB-104 |
| `effects.sign.lines` | Sign lines / SB-106 |
| `effects.victim-ghost.label` | Explicit ghost label with victim `{victim}` / SB-108 |
| `effects.advancement-toast.lines` | False toast lines / SB-109 |
| `effects.boss-bar.lines` | Brief bar labels / SB-110 |
| `effects.false-death.line` | Private false-death template with living subject `{player}` / SB-111 |

Single-line catalogue text is bounded by SB-115's 160 visible code points and
has no click actions; only the specifically approved built-in templates may
represent false events. Sign lines use their tighter bounds. Behaviour stays
in `config.yml`, all text and its colours stay in the message files, and both
remain editable in-game under SB-062. Inventory deception has no keys (SB-113).

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

**SB-085.** A history line carries the calendar date, not a machine instant:
`2026-10-01`, never `2026-10-01T18:06:25.535503500Z`. It shows the signed
delta and the written reason. It names **no actor**: a player rating is
anonymous until paid for (SB-082), and a system-authored event has no person
behind it to name, so a line that says "by System" only adds noise. The
reason a penalty was applied still reads in full, because that is the part
that explains the number. The exact instant stays in the audit trail, which
is where a precise time belongs.

**SB-086.** Giving and removing honor is reachable from a chest GUI, not only
from `/status give` and `/status take`. The GUI is a **surface**, not a second
set of rules: SB-081 already binds it to the same cost, cooldown, cap and audit
as the command, and the reason prompt, the confirmation and the charge are the
same path. The commands keep working: an operator with no client, and a console,
still need them.


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
- [ ] Psychosis defaults to a configurable 72-hour rolling window; eligible
      kills raise it immediately and recovery follows expiry, independently of
      status and Confidence (SB-093, SB-100).
- [ ] Status changes the tier prefix, never the message body's colour or the
      ambient trigger (SB-094, SB-096).
- [ ] Medium, high and extreme Psychosis produce progressively more frequent
      episodes, with quiet intervals and intact chat between episodes even at
      extreme; no configuration makes them constant (SB-095, SB-097).
- [ ] All readers see the same partially corrupted message; even short messages
      remain usable, prefixes and hover stay intact, and rater text is never
      parsed for colour (SB-095, SB-083).
- [ ] A duel kill changes neither status nor Psychosis; a non-duel kill raises
      Psychosis and lowers status by the configured delta, once per pair
      cooldown, bounded by the per-window cap, and never touches Confidence.
- [ ] Every sound the plugin plays can be retuned, silenced or replaced from
      `config.yml` without recompiling, and an unknown sound key logs a warning
      instead of throwing.
- [ ] Ambient effects reach only the affected player, respect their cooldowns,
      and leave no entity behind after quit or restart.
- [ ] Fake connection messages name only the affected player and reach only
      that player; phantom mobs vanish immediately and have no damage,
      collision, drops or server-side presence (SB-098, SB-099).
- [ ] Neither ambient episodes nor chat corruption change any metric or money
      (SB-100).
- [ ] There is no `/status effects` opt-out or effective stored opt-out flag
      (SB-101).
- [ ] Every catalogue effect respects its minimum level, private delivery,
      cooldown, session cap, quiet interval and cleanup after relog/restart;
      private sky and fake blocks never alter the world (SB-102 through SB-116).
- [ ] A victim ghost uses only the killer's own eligible victim history, skips
      without a known victim and cannot impersonate a real player's presence;
      false death requires another visible living player (SB-108, SB-111).
- [ ] Extreme-only nausea is acknowledged as impairing play, lasts at most
      40 ticks, occurs at most twice per session and at least 6,000 ticks apart,
      never stacks and leaves unrelated effects intact (SB-112).
- [ ] No inventory or held-item deception exists; configurable coloured fake
      connection templates and bounded custom lines live in both message files,
      with rejected entries identified on load and edit (SB-113 through SB-116).
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
