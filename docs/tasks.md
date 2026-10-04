# Tasks — Release 1

Units of work for `docs/plan.md`. Each task names the requirements it satisfies
and how it is proven done. Status values: `todo`, `in progress`, `review`,
`done`, `blocked`.

Every task is implemented by Antigravity in its phase worktree and reviewed by
Codex before Claude commits.

**This file is the project's state.** It is updated as work moves, not at the
end. Anyone — human or agent — reads it to learn where the project is, so a
stale `todo` beside finished work is a defect in its own right. There is no
second place where progress is recorded.

## Phase status

| phase | branch | state |
|---|---|---|
| P0 Build foundation | `feat/p0-build-foundation` | **done** — gate met on Paper 26.3 build 135 with LuckPerms, Vault, EssentialsX, WorldEdit and WorldGuard loaded |
| P1 Storage and domain | `feat/p1-storage-domain` | **done** — 86 tests green; eleven review findings closed, six accepted with reasons recorded in the commit |
| P2 Configuration | `feat/p2-configuration` | **done** — 157 tests green in both languages; thirteen review findings closed across three rounds |
| P3 Status, tiers, chat | `feat/p3-status-chat` | **done** — 200 tests green in both languages; eighteen review findings closed across two rounds, one rejected by decision; status-based chat gradient superseded by P13 |
| P4 Commands, permissions | `feat/p4-commands-permissions` | **done** — 234 tests green in both languages; sixteen review findings closed across four rounds, plus three defects found only on a live server |
| P5 Duels | `feat/p5-duels` | **done** — review closed, 283 tests green in both languages (`7eb93ab`); T-062 superseded by P12, T-139 carried into P12 |
| P6 Ambient effects | `feat/p6-effects` | **done** — review closed, 276 tests green in both languages (`15fc1f0`); trigger and episode behaviour superseded by P13, machinery retained |
| P7 Self-update | `feat/p7-selfupdate` | **done** — 291 tests green in both languages (`3c11ae6`); Codex review closed |
| P8 Legacy import | `feat/p8-legacy-import` | **done** — review closed, 263 tests green in both languages (`ca72c41`) |
| P9 Rating decay | `feat/p9-decay` | **done** — review closed, 267 tests green in both languages (`2b83756`) |
| P10 History GUI, anonymity | `feat/p10-gui` | **done** — review closed, 460 tests green in both languages (`7b9a0a0`) |
| P11 Hardening | `integration/r1` | **done** — released as v1.0 (`477107a`); the live boxes never walked (name change, two-client catalogue, `language: en`, reload mid-effect) move to T-207 |
| P12 Kill penalty, configurable sounds | `feat/p12-kill-sounds` | **done** — review closed, 417 tests green in both languages (`f3fc2e9`) |
| P13 Psychosis effects and chat | `feat/p13-tuning` | **done** — released in v1.0; T-169/T-174 two-client walks move to T-207 |
| P14 Release 2 | `integration/r2` | **done** — 882 tests green, Codex review closed, owner and bot acceptance passed; ready to release as v2.0 |
| P15 Release 2.0.1 | `main`, tag `v2.0.1` | **done** — 946 unit tests and 15/15 live bot scenarios green; owner checked tab prefix, effects, anonymous head, two-click reveal, EssentialsX chat and name hover live |
| P16 Release 2.0.2 | `main`, tag `v2.0.2` | **done** — 971 unit tests green; signed with the primary release key; T-230 history date awaits the owner's live look |

An integration branch, `integration/r1`, carries P4 through P7 merged together
and is the base for P10 and P12. It exists because three phases branched from
P4 in parallel and each added a constructor to the same two classes.

Reference produced along the way: `docs/reference/paper-26.3-notes.md` settles
the 26.3 API for every later phase, and corrected two things this project had
already written down wrongly.

## Decisions waiting on the product owner

The remaining question does not block current work; its configuration landed
during P2.

- **Confidence decay.** Exponential half-life, `w = 2^(-age/halfLife)`, default
  30 days. The default threshold of exactly `1.0` means a single recent actor
  reads `Low` only during the rating's first second, then `Unknown`.

The Psychosis window is settled at a configurable **72-hour default** (SB-093,
decision 0005); P13 replaces the implemented 24-hour default. The existing
configurable kill thresholds (medium 2, high 5, extreme 10) are unchanged by
this decision.

---

## P0 — Build foundation

| id | task | spec | status |
|---|---|---|---|
| T-001 | Add the Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/`). Maven is not installed locally. | — | done |
| T-002 | Retarget `pom.xml`: `maven.compiler.release` 25, `io.papermc.paper:paper-api:26.3.build.135-beta` provided, Paper repository, Vault API provided. Drop the Spigot dependency and repository. | §3 | done |
| T-003 | Move `plugin.yml` and `config.yml` from `src/main/java` to `src/main/resources`; remove the explicit `<resource>` block from `pom.xml`. | — | done |
| T-004 | Rename package to `com.dasannn.socialblueprint`; rename entry class `main` to `SocialBlueprintPlugin`; update `plugin.yml`. | — | done |
| T-005 | Delete `PrefixManager`. It is entirely commented out and wrongly extends `JavaPlugin`. | SB-12.4 | done |
| T-006 | Reduce the entry class to a skeleton: enable, disable, logging, no feature code. Remove `/pstatus evaluate`, the `eco` dispatch, the death item rewards and `/utils`. | §13 | done |
| T-007 | Declare `sqlite-jdbc` under `libraries:` in `plugin.yml`. | — | done |

**Gate:** Paper 26.3 starts with the jar and no configuration file; enable and disable are clean in the log.

---

## P1 — Storage and domain

| id | task | spec | status |
|---|---|---|---|
| T-010 | Domain value types: `PlayerId` (UUID), `Status`, `Tier`, `ConfidenceLevel`, `PsychosisLevel`, `ReputationEvent`, `HonorKind`. Pure Java, no Bukkit import. | SB-001, SB-060 | done |
| T-011 | Tier ladder resolution over signed, strictly ordered thresholds. Nine tiers. Exactly `0` resolves to the neutral tier. | SB-010, SB-011, SB-012 | done |
| T-012 | Derive status from an event list. No standalone authoritative integer. | SB-002 | done |
| T-013 | Reputation Confidence from the count of distinct actors, weighted by rating age. Repeated ratings from one actor do not raise it. | SB-003 | done |
| T-014 | Killing Psychosis over a rolling window. Never reads or writes social status. | SB-004 | done — window default revised by SB-093; see T-150 |
| T-015 | Honor cost: fixed base times the progressive multiplier for the actor's ratings inside the window. | SB-050 | done |
| T-016 | Per-pair allowance: at most three positive and three negative per actor-target pair inside the window; independent counts; the window expiring restores it. | SB-054 | done |
| T-017 | SQLite schema and numbered migrations driven by `schema_version`. Tables per `ARCHITECTURE.md` §4. | — | done |
| T-018 | Repositories over a single-threaded executor; one connection; no pool. | — | done |
| T-019 | In-memory status cache, invalidated on write, rebuildable from events. | — | done |
| T-020 | Domain tests: tier resolution across the full range including `0` and both extremes; cost with each multiplier step; cap expiry across a window boundary; Confidence over distinct versus repeated actors; status derived from an event list. | §14 | done |
| T-021 | Storage tests against an in-memory database, including the full migration chain. | — | done |

**Gate:** T-020 and T-021 pass.

---

## P2 — Configuration

| id | task | spec | status |
|---|---|---|---|
| T-030 | Typed immutable config records for every section; one load, no scattered `getString`. | — | done |
| T-031 | Load-time validation: malformed tier ladder, missing required key, negative cooldown, or a tier threshold out of order fails the enable with a message naming the key. Never a silent default. | SB-011 | done |
| T-032 | Split text into per-language message files; every player-visible string configurable. | SB-062 | done |
| T-032a | Ship `messages_es.yml` and `messages_en.yml` with identical keys, both inside the jar and both written to the data folder on first run. A `language:` key in `config.yml` selects one. | SB-066, SB-067 | done |
| T-032b | Missing-key fallback to the other language, with a one-time warning naming the key. A raw key must never reach a player. | SB-068 | done |
| T-032c | A test that fails when the two language files' key sets diverge, and one that fails when a player-visible string is hardcoded in Java. | SB-069 | done |
| T-032d | Move the nine tier display names out of `config.yml` into the language files, keyed by tier. `config.yml` keeps each tier's prefix token and threshold. Supply the English names. | SB-070i | done |
| T-033 | Colour parsing through Adventure's legacy serializer with `&` and hex. One interpretation only; never also MiniMessage. | SB-063 | done |
| T-034 | Atomic reload: replace the snapshot wholesale; nothing caches derived values across a reload. | SB-013 | done |
| T-035 | `/status config <key> [value]`: read and edit in-game through the same validation, persist, publish a new snapshot. | SB-062 | done |
| T-036 | Ship a `config.yml` whose tier ladder is correct — negative tiers carry negative thresholds. The baseline shipped positive ones. | SB-011 | done |

**Gate:** a malformed ladder names its key and refuses to enable; an in-game colour edit applies with no restart; switching `language:` changes every player-visible string with no other edit.

---

## P3 — Status, tiers, prefixes, chat

| id | task | spec | status |
|---|---|---|---|
| T-040 | Resolve the prefix per lookup from the current snapshot. No static caching at enable. | SB-013 | done |
| T-041 | Chat gradient from `#202020` to bright white by tier. Never absolute black, never hidden, truncated, delayed or blocked. | SB-020, SB-021 | done — superseded by SB-094/SB-095; see T-154 |
| T-042 | Chat colouring reads an immutable snapshot inside `AsyncChatEvent` and touches nothing else. | — | done — async snapshot path retained for T-154, colouring superseded |
| T-043 | Name hover: status, tier, Confidence, Psychosis, count of distinct contributors. | SB-022 | done |
| T-044 | Coexist with other prefix plugins: never overwrite display, list or custom name unconditionally. | SB-014 | done |
| T-045 | `/status [player]` profile output, including offline targets. | SB-005 | done |

**Original gate met:** nine tiers resolve across the full range; minimum-status
messages were near-black and readable. The gradient check is superseded by
P13; its replacement checks shared partial corruption and usable chat.

---

## P4 — Commands and permissions

| id | task | spec | status |
|---|---|---|---|
| T-050 | `/status` dispatcher with `/pstatus` and `/reputation` aliases. Sender resolved before dispatch, so the console never reaches player-only code. | SB-065 | done |
| T-051 | Centralised argument parsing. No subcommand calls `Integer.parseInt` on raw input. | SB-065 | done |
| T-052 | Offline target resolution by UUID from `player_profile`, falling back to Bukkit's offline lookup. | SB-060, SB-065 | done |
| T-053 | Declare `socialblueprint.*` nodes with explicit defaults. Keep existing `pstatus.*` grants working — children flow parent → child, so the legacy node is the parent or the check consults both (ARCHITECTURE §7). Verify against a real LuckPerms grant. Drop `pstatus.evaluate`. | SB-061 | done |
| T-054 | Configurable action-to-node mapping, resolved at check time. Nodes are not invented at runtime. | SB-061 | done |
| T-055 | `/status admin give|take|reset`: free, no cooldown, no cap, audited. Reset writes a compensating event, never a delete. | SB-058 | done |
| T-056 | Audit rows for every administrative action: actor, operation, target, before, after, time. | SB-064 | done |
| T-057 | Vault resolution at enable; disable with a clear reason if no provider. | SB-051 | done |
| T-058 | Charge and event commit together, with refund on write failure. `EconomyResponse` is checked. | SB-057 | done |

**Gate:** console runs every command without an exception; a name change does not detach a record; every admin action is audited.

---

## P5 — Duels

| id | task | spec | status |
|---|---|---|---|
| T-060 | Duel lifecycle: challenge, accept, deny, leave, expiry. 1v1 and group. | SB-030 | done |
| T-061 | A kill inside an active duel affects neither status nor Psychosis. | SB-031 | done |
| T-062 | A kill outside a duel raises Psychosis. Removes the baseline deduction at `EventManager.java:24-29`. | SB-032, §13 | done — the "never changes status" half is superseded by ADR 0004; see P12 |
| T-063 | Configurable disconnect handling, distinguishing a combat log from a normal quit. | SB-033 | done |

**Gate:** duel kill changes neither metric; open-world kill raises Psychosis.
Met. The status side of an open-world kill moved to P12 after ADR 0004.

---

## P6 — Ambient effects

Parallel with P5.

| id | task | spec | status |
|---|---|---|---|
| T-070 | Effect scheduler below a configurable status threshold, with an independent cooldown and per-session cap per effect. | SB-040, SB-043 | done — status gate superseded; scheduler and limits reused by T-152 |
| T-071 | Delivery to the affected player only. Nothing reaches other players, the real chat, or the server log. | SB-041 | done |
| T-072 | Speed III silverfish: no damage dealt or taken, no targeting, no loot, no XP, not persistent, removed on timer. | SB-042 | done — timed encounter superseded by SB-099; see T-153 |
| T-073 | Entity registry cleaned on despawn timer, quit, world change and disable. No entity survives any of them. | SB-042 | done — cleanup machinery retained for SB-099/T-153 |
| T-074 | Near-black short chat lines, creeper fuse sound, fake join and leave announcements — all private. | SB-040 | done — eligibility and fake-message subject superseded by SB-096/SB-098; see T-152/T-153 |
| T-075 | `/status effects` per-player opt-out, persisted. Changes no metric and hides nothing from others. | SB-044 | done |

**Gate:** effects are private; no entity leaks; opt-out works.

---

## P7 — Self-update

| id | task | spec | status |
|---|---|---|---|
| T-080 | `GET /repos/<owner>/<repo>/releases/latest` via `java.net.http`, on the executor, never on the main thread. Configurable repository and channel. | SB-073, SB-076 | done |
| T-081 | `/status version`: running version versus latest release. | SB-070 | done |
| T-082 | `/status update`: download the asset, verify its checksum, write to `plugins/update/`. A mismatch aborts and leaves the directory untouched. | SB-071, SB-074 | done |
| T-083 | Report that a restart is required. Never restart the server. | SB-072 | done |
| T-084 | Startup check on by default, automatic download off by default. | SB-075 | done |
| T-085 | Every failure is a logged warning only. Never blocks startup, never delays a tick. | SB-073 | done |

**Gate:** correct with GitHub reachable and unreachable; a verified jar is applied on restart; a corrupted download changes nothing.

---

## P8 — Legacy import

| id | task | spec | status |
|---|---|---|---|
| T-090 | Read an old PlayerStatus `config.yml`; write one `legacy_import` event per player, no actor. | — | done |
| T-091 | Legacy events contribute nothing to Reputation Confidence. | SB-003 | done |
| T-092 | Import is idempotent and reports what it did. | — | done |

**Gate:** a real old configuration imports with no score loss and invents no evidence.

---

## P9 — Rating decay

| id | task | spec | status |
|---|---|---|---|
| T-110 | Age-weighted contribution of a reputation event to social status, curve configured in YAML. No event is ever deleted or rewritten. | SB-006 | done |
| T-111 | Decay configuration is independent of Confidence's age weighting. Changing one must not change the other; a test proves it. | SB-006, SB-003 | done |
| T-112 | Symmetric tier ladder: negative thresholds at `-5`, `-15`, `-30`, `-50`. Migrate an existing configuration without reclassifying anyone silently — report what moved. | SB-011a | done |

**Gate:** an old negative event loses weight over a simulated year while the
event itself is still readable in the history; falling a tier costs the same
number of points as rising one.

---

## P10 — Rating history GUI and anonymity

| id | task | spec | status |
|---|---|---|---|
| T-120 | Double chest GUI from `/status [player]`: subject head, tier-coloured dye, green and red banners. | SB-080 | done |
| T-121 | History grid: rater head, paper with the written reason, direction banner. Edge banners page forward and back. | SB-080 | done |
| T-122 | The GUI opens from cache or a completed async load. No blocking read on the main thread, no inventory work off it. | SB-081 | done |
| T-123 | A rating made in the GUI goes through the same honor path as the command: same cost, cooldown, cap and audit. | SB-081 | in progress — owner follow-up: optional give reason; Claude build pending |
| T-124 | Rater names hidden by default; revealing one charges a configurable amount through Vault and is remembered per viewer. | SB-082 | done |
| T-125 | Comments are length-bounded and rendered as plain text — no colour codes, formatting or click actions, whatever the rater typed. | SB-083 | done |
| T-126 | Anonymity is cosmetic only: the SB-054 cap still counts per actor-target pair, and administrators and the audit trail still see everything. | SB-084 | done |

**Gate:** a rating made in the GUI is indistinguishable in storage from one made
by command; a rater's name is hidden until paid for; a comment containing colour
codes renders as literal text.

---

## P11 — Hardening

| id | task | spec | status |
|---|---|---|---|
| T-100 | Walk every acceptance criterion in `docs/spec.md` §14 on a running Paper 26.3 server; record evidence per box. | §14 | in progress — console boxes done on a clean server: migration 3 applies to a database stamped at 2 (`kill_penalty_claim` created, 16 profiles intact); the nine tiers resolve; six distinct raters give Confianza Establecida while eight ratings from one give Desconocida; Psicosis Media from two open-world kills with the duel kill not counted; fifteen console branches with zero exceptions; config read, write and persistence with no restart, including the two leaves added by T-103; every administrative action audited with before and after; the language switch changes every console surface live. Remaining: two live clients (duels, kill penalty, sounds, GUI, hover, chat gradient), a real name change, the update handoff, and a profiler for the main-thread I/O box |
| T-101 | Confirm no file or database I/O happens on the main thread. | §14 | done — JFR over a 55 min live session (chat, kills, honor, config edits, GUI): the only SocialBlueprint file I/O on the server thread is the synchronous config and message load inside `onEnable`, before any player can join; none during play |
| T-102 | Fresh install with no configuration, and install over an old PlayerStatus configuration. | §14 | done — upgrade over live data keeps owner values (whisper 10 s adopted into private-chat, retired keys gone, Migration 5 applied); fresh install starts clean, `/status import` reads 5, imports 3 UUID rows, skips 2 name-only rows, and is idempotent on rerun |
| T-103 | Codex reviews the full release diff, not phase by phase. | — | done — P13-inclusive review found 5 defects, 3 risks, 1 test-policy issue; all fixed in `review/r1-final-p13` (698 tests) |
| T-104 | Upgrading merges new configuration sections and new message keys into the server's existing files, keeping every stored value. Found on a live upgrade: the new sections load from defaults but cannot be read or edited in game, which breaks constitution §2.8 on the upgrade path, and a missing message key warns on every run. | §2.8, SB-062, SB-068 | done |
| T-106 | `/status admin` usage omits `import`, added in P8, so the only discoverable way to find the command is the source. Every subcommand appears in its usage line. | SB-065 | done — `d9386d0` |
| T-140 | The GUI renders the tier, Confidence and Psychosis as raw enum names (`StatusGuiService.java:402-404`), so the subject head reads `MEDIUM` and `ESTABLISHED` in any language while the command translates all three. Found on the running server. | SB-016, §7 | done — `100b94f`, verified in game: dye, Confidence and Psychosis render translated |
| T-141 | A history line reads as elapsed time in the player's language, not a machine timestamp; the exact instant stays on hover and in the audit trail. | SB-085 | done — `100b94f`, verified in game: `2026-10-01`, signed delta, reason, no actor |
| T-142 | Giving and removing honor is reachable from a chest GUI, through the same cost, cooldown, cap, reason prompt, confirmation and audit as the command. | SB-086 | done — GUI-started honor confirms in a chest; commands keep the chat preview; live click check pending |

**Gate:** every box in §14 ticked with evidence.

---

## P12 — Kill penalty and configurable sounds

Governed by `docs/decisions/0004-a-non-duel-kill-costs-status.md`. Reopens the
status half of T-062 and removes the last hardcoded sound.

| id | task | spec | status |
|---|---|---|---|
| T-130 | A kill outside a duel writes a system-authored reputation event: actor `SYSTEM`, cost `0`, message-key reason, YAML delta (default `-1`). Stored, derived and decayed like any other event. | SB-032 | done |
| T-131 | The system event never raises Reputation Confidence, and Psychosis still never moves status. A test asserts both metrics move only by their own rule. | SB-034, SB-004 | done |
| T-132 | One penalty per killer-victim pair per configurable cooldown, plus a configurable per-window cap on total automatic loss. | SB-035 | done |
| T-133 | Penalty skipped inside a duel, when the killer cannot be identified, and in YAML-exempt worlds. Delta `0` disables the feature. | SB-036, SB-031 | done |
| T-139 | An attack's duel context is decided when it lands, not when the victim dies: an arrow fired before consent that kills after the duel starts is an open-world kill, and one fired during a duel that lands after it ends is not. Carried over from the P5 review. | SB-031, SB-032 | done |
| T-134 | The system event renders in the history GUI and in `/status history` like any other, with its reason translated from the message key. | SB-032, SB-080 | done |
| T-135 | `sounds:` section in `config.yml`: one named slot per sound, each with key, volume, pitch and category. Every existing sound — starting with the creeper fuse in `AmbientEffectDispatcher` — reads its slot instead of a constant. | SB-090 | done |
| T-136 | An empty slot plays nothing; an unrecognised key logs a warning naming the slot once and plays nothing. Neither ever throws or blocks the action the sound accompanied. | SB-090 | done |
| T-137 | Slots are reloadable in-game with the rest of the configuration, and private sounds still reach only the affected player. | SB-091, SB-041, SB-062 | done |
| T-138 | A slot may hold several layers, each with its own key, volume, pitch, category and tick delay; they play in order from one trigger. The single-mapping form still means one layer at delay `0`. | SB-092 | done |

**Gate:** a non-duel kill lowers status once per pair cooldown, bounded by the
cap, never touching Confidence; every sound can be retuned or silenced from
`config.yml` with no restart and no recompile.

---

## P13 — Psychosis drives effects and chat

Governed by decisions 0005 and 0006. Reopens the P3 gradient and the P6 trigger,
not the engine itself. Tasks below are in dependency order; P11's final
acceptance gate depends on T-174. Earlier completed gates describe the former
behaviour and do not prove these new requirements.

| id | task | spec | status |
|---|---|---|---|
| T-150 | Change the configurable Psychosis window default from 24 to 72 hours and apply the revised default on upgrade while retaining deliberate owner overrides. Verify immediate rises, expiry at the configured boundary and recovery without reading or writing status or Confidence. Reuse the existing window calculation and event history. | SB-093, SB-004, SB-100 | done — P13 core round |
| T-151 | After T-150, wire validated, live-editable Psychosis episode frequency and partial chat-corruption limits. Retire the ambient status threshold and status message-gradient settings. Configuration cannot permit constant episodes, overlapping sound layers without silence, or fully destroyed messages; preserve unrelated stored settings on upgrade. | SB-094, SB-095, SB-096, SB-097, SB-062 | in progress — owner third round: wider word spread and graded episode body colours; Claude build pending |
| T-152 | After T-151, replace the status gate with Psychosis and its medium/high/extreme gradation. Reuse the existing scheduler, per-player cooldowns and session caps, and configurable layered sounds; enforce quiet intervals through the final delayed layer's audible tail. Lowest Psychosis delivers none; remove the opt-out command and effective stored flag under SB-101. Verify equal Psychosis behaves alike across different statuses. | SB-096, SB-097, SB-041, SB-043, SB-101, SB-090, SB-091, SB-092 | done — P13 core round |
| T-153 | After T-152, make private fake connection messages name their recipient and make phantom mobs appear briefly outside interaction reach. Reuse the managed-entity cleanup for disappearance, quit, world change and disable; ensure packet-only fakes have no damage, collision, targets, drops or persistence. Verify no bystander receives either effect. | SB-098, SB-099, SB-041 | review — owner tuning implemented: timed hostile phantom, non-peaceful skip and duration/cadence/chat defaults; Claude build pending |
| T-154 | After T-151, remove status colouring of the message body while retaining the tier prefix and name hover. Add partial Psychosis corruption to the existing async chat snapshot path: choose one result per message for all readers, preserve usable text and short messages, and leave intact messages between episodes at every level. Rater comments remain plain text and are never parsed for colour. | SB-094, SB-095, SB-097, SB-022, SB-083 | review — owner third round adds shared episode darkening and wider corruption; Claude build pending |
| T-155 | After T-153 and T-154, add focused plain-data checks for window expiry, level gradation, quiet intervals including sound layers, shared message text and short-message readability. Assert neither effects nor chat corruption writes status, Confidence or money; retain duel exclusions and the independent non-duel kill penalty. | SB-093, SB-095, SB-097, SB-100, SB-031, SB-032, SB-034 | done — plain-data checks across P13 rounds |
| T-156 | After T-155, verify the updated core §14 criteria on Paper with two clients, in both languages and after live reload/upgrade. Check self-named private messages, brief scaled harmless phantoms outside reach and cleanup, removal of opt-out, uniform usable corrupted chat, extreme-level silence and intact messages, and absence of status-driven effects. Reuse the existing live-server acceptance procedure; T-169 extends it to the full catalogue. Claude runs the build; agents do not run Maven. | SB-093 through SB-101, SB-041, SB-043, SB-083, §14 | todo |
| T-157 | After T-151, add the SB-116 catalogue behaviour keys and bilingual message keys, coloured fake-connection templates and custom-line lists. Reuse typed snapshots, sound-slot references, message fallback, validation, live editing and upgrade merging; preserve existing owner settings. Validate level floors, finite duration/range/count, positive quiet intervals, line lengths after substitution, no click actions and no misleading custom system notices. Add no inventory-deception or nausea keys; retire obsolete nausea settings. | SB-102 through SB-116, SB-062, SB-063, SB-067, SB-068 | done — catalogue keys across P13 rounds |
| T-158 | After T-152 and T-157, extend the existing episode lifecycle to track visual expiry, restoration and final audible tails before quiet time starts. Reuse cooldowns, session counters, scheduler cancellation and managed-fake cleanup; add only the restoration actions needed by the catalogue. Reload and level changes cannot reset caps; skips consume no delivery; quit/world change/stop cancel delivery and restore temporary state. | SB-097, SB-043, SB-102 through SB-116 | done — `2ae3591` |
| T-159 | After T-158, add High private night/storm through per-player time/weather and restoration of the prior presentation. Reuse scheduling and cleanup; add the thin private-sky renderer, never modify world time/weather or overwrite a newer external override. | SB-102, SB-116 | done — `2ae3591` |
| T-160 | After T-158, add Medium private particles and title/action-bar flashes. Reuse episode limits, message rendering and cleanup; add recipient-only visual delivery with bounded count/radius/fades, clearing only owned presentation. | SB-103, SB-104, SB-116 | done — `2ae3591` |
| T-161 | After T-158, add Medium source-less footsteps/door/anvil/bow/cave sounds and High hurt flash with damage sound. Reuse existing named layered slots and tick delays, including relative sound placement and audible-tail bounds; add only the cosmetic hurt renderer, with no damage event, health loss, knockback or combat-state change. | SB-105, SB-107, SB-090 through SB-092, SB-116 | done — hurt flash in 92c159c, sourceless sounds in 2ae3591 |
| T-162 | After T-158, add High recipient-only fake blocks and signs. Reuse scheduling, message files and restoration cleanup; add sendBlockChange/sendSignChange delivery and restore current true block/sign data. Exclude blocks being used, stood on or held and any change to client collision, selection, targeting, reach or interaction behaviour; skip replacements without guaranteed equivalence; never modify the world. | SB-106, SB-113, SB-116 | done — 92c159c; live check pending (T-169) |
| T-163 | After T-153 and T-158, add High stationary victim ghosts. Reuse asynchronous psychosis_event reads, UUID/name resolution and managed packet-only fake cleanup; add only the victim selection and ghost presentation. Skip without a resolvable eligible victim from this killer's own history; forbid real skins/account identities, movement, speech, interaction and bystander delivery. | SB-108, SB-099, SB-116 | in progress — owner follow-up: default-skin mannequin; Claude build/live check pending (T-169) |
| T-164 | After T-158, add Medium false advancement toasts and brief boss bars. Reuse messages, scheduler, episode limits and owned-presentation cleanup; add private UI renderers without real advancement criteria, rewards, statistics or bosses. | SB-109, SB-110, SB-116 | done — boss bar in bad9a84; toast not delivered on Paper 26.3 (SB-109 note) |
| T-165 | After T-153 and T-158, add High private false death messages about another nearby visible living player, skipping when none qualifies. Apply Medium coloured configurable self-connection templates and Medium built-in/custom hallucination lines. Reuse private-chat delivery and message files; custom lines share its cap/cooldown, create no event logs and cannot impersonate server notices. | SB-111, SB-114, SB-115, SB-098, SB-116 | done — bad9a84; live check pending (T-169) |
| T-167 | After T-159 through T-165, review all catalogue delivery paths for the rejected inventory/held-item deception. Reuse code review and existing ownership checks; no new effect machinery. Confirm no fake affects another player, real world state, status, Confidence or money, and no mechanical advantage or impairment exists, including client collision/selection changes from fake blocks. Confirm withdrawn SB-112 has no application path. | SB-102 through SB-116, especially SB-113, SB-100 | done — covered by the P13-inclusive T-103 review; no inventory deception or nausea path |
| T-168 | After T-167 and T-155, extend existing plain-data tests with catalogue level floors, shared quiet intervals through restoration and final sound tails, session caps across reload/level changes, client collision/selection equivalence, own-history victim selection/no-victim skip, living false-death eligibility and text/config validation. Reuse the domain/storage test approach; keep Bukkit registry objects out of unit tests and leave packet rendering to T-169. | SB-102 through SB-116, SB-097, SB-043 | done — plain-data checks in WorldFakesTest, UiFakesTest, PresentationEffectsTest |
| T-169 | After T-168 and T-156, extend the existing Paper acceptance walk with two clients, both languages and live reload/upgrade. Exercise every catalogue effect at its floor and below it, prove recipient-only delivery, true sky/block restoration and no trace after relog/restart or interrupted cleanup. Check ghost identity restrictions/no-victim skips, real advancement history unchanged, living false-death subject, absence of nausea or mechanical consequences, configurable colours/custom lines and no inventory or metric/money changes. Reuse live-server verification; Claude runs builds, agents do not run Maven. | SB-102 through SB-116, SB-100, SB-101, §14 | todo |
| T-170 | Extend the existing Psychosis calculation and persistence with the serenity direction and credited active-play duration; keep one metric, existing kill history and rolling-window expiry. After T-150, implement active-play/idle/AFK accounting, no offline/backfilled credit, finite diminishing-return curve and ceiling; an eligible kill resets credited serenity even if its independent status penalty is suppressed. Reuse eligibility and profile snapshots, initialise upgrade credit at zero and persist asynchronously without changing status or Confidence. | SB-004, SB-093, SB-117, SB-118, SB-119 | done — serenity accounting, Migration 5 |
| T-171 | After T-170 and T-157, add SB-125 validated live configuration and bilingual neutral/serenity labels to existing profile, hover, GUI and Psychosis detail. Reuse typed snapshots, sound slots and upgrade merging. Preserve credited time across reload/relog/restart, clamp/recompute on curve edits and keep all three metrics distinct. Add no serene title/action-bar or nausea keys. | SB-117, SB-125, SB-062, SB-068 | in progress — owner follow-up: separate madness/serenity lines; Claude build pending |
| T-172 | After T-171 and T-159/T-161/T-153/T-160, reuse private-sky, layered-sound, particle and managed-fake renderers for dawn, clean sourceless sounds, gentle particles and cat/fox/wolf apparitions. Extend the existing episode audience to local visible observers for all except dawn; no new engine. Respect vanish, range and world changes, subject-owned caps, direction-change cleanup and quiet time; no real animals, mechanical changes or title lines. | SB-120 through SB-125, SB-100, SB-043 | done — apparition only outside reach (8 blocks default) |
| T-173 | After T-172 and T-168, extend existing plain-data checks for neutral/serenity exclusivity, active versus idle/offline time, upgrade/restart credit, diminishing equal-interval gains, exact finite ceiling, no banked surplus, eligible-kill reset versus duel exemption and independent penalty limits, configuration edits and audience/cleanup decisions. Assert metric separation, private dawn, no title and no mechanical advantages; leave Bukkit rendering for T-174. | SB-117 through SB-125, SB-004, SB-100 | done — plain-data checks in SerenityEffectsTest |
| T-174 | After T-173 and T-169, extend the existing live Paper walk with two clients, both languages, reload/upgrade and direction changes. Verify a reachable ceiling using configured playtime, actual peaceful activity versus idle/offline time, immediate eligible-kill reset, translated existing displays, shared local sounds/particles/apparitions and private dawn. Check vanish/range/world departures, interrupted restoration, no animals or mechanical benefits, no serene title or nausea, and no cap/cooldown bypass. Claude runs builds; agents do not run Maven. | SB-117 through SB-125, SB-100, §14 | todo |

**Gate:** Psychosis drives private episodes and shared partial chat corruption;
status retains only its tier prefix in chat. Every level leaves quiet intervals
and usable messages. Madness recovery follows the configurable 72-hour default
window; serenity then
grows with active peaceful play, with diminishing returns to a finite ceiling.
One eligible kill resets serenity; neither direction reads or alters status.
No presentation changes status, Confidence or money, and no fake or temporary
override survives cleanup, relog or restart. Medium introduces the mild
catalogue and High the stronger fakes; no level permits mechanical impairment.
Serenity shares clean sounds, gentle particles and kindly apparitions locally,
while dawn remains private; no serene title exists. Inventory deception is
forbidden and no opt-out remains. T-174 supplies the full-catalogue and serenity
evidence for P11's revised acceptance boxes.

---

## P14 — Release 2

Spec §15 and §16, decision 0007. Branches start from `main` at `943deb2` and
merge into `integration/r2`. T-200, T-202, T-203 and T-204 run in parallel;
T-201 and T-205 follow T-200; T-206 follows everything else.

| id | task | spec | status |
|---|---|---|---|
| T-200 | Signed mental-state value: storage, `mind_event` log, migration from release 1, level thresholds, the apply rule (no spill past Neutral, linear, capped), kill input reusing R1 eligibility, admin `mind reset` / `reset-all`, configuration and upgrade merging. Retire the 72-hour window and the active-hours curve. | SB-130 to SB-132, SB-133 (kill), SB-135 to SB-137 | done — `23d39dc` |
| T-201 | After T-200, the remaining inputs: death, near-death, sleepless night, clean day, sleep and the five peaceful actions, with duel exemption, idle rule and rolling 24-hour caps. | SB-133, SB-134 | done — merged, 793 tests green |
| T-202 | Low effects and floors, `max-concurrent` per level, false-death line list, particle type lists, serene apparition kind list with turtle, fox, armadillo and bee. | SB-139 to SB-143 | done — `2bfa7db`, 757 tests green after merge |
| T-203 | Mandatory reasons, word filter for chat and reasons, admin revoke from the GUI and the command. | SB-150 to SB-152 | done — merged, 768 tests green at merge |
| T-204 | Filter the `plugin.yml` version from `pom.xml`. | SB-155 | done — `18c522c`, 735 tests green |
| T-205 | After T-200, one mental-state line in hover, profiles, chest and `/status psychosis`, both languages. | SB-138 | done — merged, 793 tests green |
| T-206 | After T-201 to T-205, the `/status admin features` switch GUI over every input and effect. | SB-145 | done — 813 tests green |
| T-208 | Honor ratings as a mental-state input with a 3-per-24-hours cap, revocation reversal. | SB-146 | done — 804 tests green |
| T-207 | Live acceptance walk for §16, including the release-1 boxes carried over. | §16 | done — owner walk on sb-testserver plus bot suite 10/10 on the 2.0 build (`254381a`); real account name change still unwalked |
| T-209 | Owner round 1: time left to rate, revoked ratings hidden from players, short config keys, `mind set`. | SB-156 to SB-159 | done |
| T-210 | Owner round 2: stale views after mind writes, quoted fake-connection templates repaired, `effects.debug`. | SB-138, SB-114 | done |
| T-211 | Apparition in view, ten-second dawn, untouched legacy kinds adopt the new defaults. | SB-124, SB-125 | done |
| T-212 | Updater stages only strictly newer releases; stale staged jars removed. | SB-071 | done |
| T-213 | Apparition lasts 15 s and follows at a distance. | SB-124 | done |
| T-214 | Revoke by rating id alone. | SB-152, SB-159 | done |
| T-215 | Test fixtures no longer decay against the wall clock. | — | done |
| T-216 | Private sky storms at High, night and storm together at Extreme. | SB-102 | done — 882 tests green |


## P15 — Release 2.0.1

Owner decisions, 2026-10-03, decision 0008. Claude runs the build and live
acceptance; this worktree does not run Maven.

| id | task | spec | status |
|---|---|---|---|
| T-217 | Balance-based honor quote and exact confirmation charge, removal of progressive pricing, legacy config adoption and validation tests. | SB-050, SB-052, decision 0008 | done |
| T-218 | Shared chat/tab tier prefix and name, join/status/reload updates, toggle and disable restoration, plain-data formatting tests. | SB-160 | done |
| T-219 | Session Psychosis/Serenity notice baselines at the shared rebuild, translated rises/falls, validated settings and decision tests. | SB-161 | done |
| T-220 | Plugin-wide disabled worlds: migrate old lists, suppress every mental input and active-time credit, both-sided kill/status rules, effects/chat corruption, duels and actor honor/confirm; preserve prefixes/profiles/admin, entry/reload cleanup and regression tests. | SB-162 | done |
| T-221 | Console/online/offline percentage Psychosis reduction, serialized immutable event/audit write, permission/completion/messages and math/storage tests. | SB-163 | done |
| T-222 | Six private serenity additions, validated defaults/live editing, lifecycle restoration and regression coverage. | SB-164 to SB-170 | done |
| T-223 | Seven private Psychosis additions, level floors, bounded visuals/sound tails, cleanup and regression coverage. | SB-171 to SB-178 | done |
| T-224 | Fully anonymous history heads and immediate real skin/name on authorized or paid reveal, per-viewer persistence. | SB-082, SB-181 | done |
| T-225 | Two-click exact-cost reveal confirmation, translated lore, expiry/disarming, live window and charge-once coverage. | SB-052, SB-082, SB-181 | done |
| T-226 | EssentialsX Chat/foreign renderer coexistence, shared early body, prefix modes, leave mode and configuration/tests. | SB-179 | done |
| T-227 | Two-page feature GUI with all 45 switches, bounded navigation and retained page on audited toggle refresh. | SB-180 | done |
| T-228 | Consolidate merged release requirements/acceptance and README; prune attached obsolete-key comments with upgrade regression coverage. | SB-182, §15.6, §16 | done |
| T-229 | Owner manual check 2.0.1: tab prefix, the 13 effects visually, anonymous head, two-click reveal, EssentialsX chat look. | SB-160, SB-164 to SB-181 | done |


## P16 — Release 2.0.2

| id | task | spec | status |
|---|---|---|---|
| T-230 | Show the rating calendar date after every history reason in GUI and chat/console, with a validated live-editable pattern, server time zone, both languages and plain-data tests. | SB-183 | review |
| T-231 | Require detached Ed25519 signatures for manual and automatic updates using cached running-jar trust, secure bounded downloads, fail-closed cleanup, bilingual failures and regression coverage. | SB-184 to SB-186, decision 0009 | done |
| T-232 | JDK-only encrypted-key generation/sign/verify tool, regression coverage and release guide for separate backups, rotation, recovery and publishing all three assets. | SB-187, decision 0009 | done |
| T-233 | Generate and embed the release keys; sign v2.0.2 | SB-185, SB-187, decision 0009 | done |
