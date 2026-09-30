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
| P3 Status, tiers, chat | `feat/p3-status-chat` | **done** — 200 tests green in both languages; eighteen review findings closed across two rounds, one rejected by decision |
| P4 Commands, permissions | `feat/p4-commands-permissions` | **done** — 234 tests green in both languages; sixteen review findings closed across four rounds, plus three defects found only on a live server |
| P5 Duels | `feat/p5-duels` | **done** — review closed, 283 tests green in both languages (`7eb93ab`); T-062 superseded by P12, T-139 carried into P12 |
| P6 Ambient effects | `feat/p6-effects` | **done** — review closed, 276 tests green in both languages (`15fc1f0`) |
| P7 Self-update | `feat/p7-selfupdate` | **done** — 291 tests green in both languages (`3c11ae6`); Codex review closed |
| P8 Legacy import | `feat/p8-legacy-import` | in progress |
| P9 Rating decay | `feat/p9-decay` | in progress |
| P10 History GUI, anonymity | `feat/p10-gui` | not started |
| P11 Hardening | — | not started |
| P12 Kill penalty, configurable sounds | `feat/p12-kill-sounds` | in progress |

An integration branch, `integration/r1`, carries P4 through P7 merged together
and is the base for P10 and P12. It exists because three phases branched from
P4 in parallel and each added a constructor to the same two classes.

Reference produced along the way: `docs/reference/paper-26.3-notes.md` settles
the 26.3 API for every later phase, and corrected two things this project had
already written down wrongly.

## Decisions waiting on the product owner

Neither blocks current work; both land in `config.yml` during P2.

- **Confidence decay.** Exponential half-life, `w = 2^(-age/halfLife)`, default
  30 days. The default threshold of exactly `1.0` means a single recent actor
  reads `Low` only during the rating's first second, then `Unknown`.
- **Psychosis window.** 24 hours, thresholds medium 2, high 5, extreme 10
  kills. On an active PvP server ten kills in a day is an ordinary afternoon.

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
| T-014 | Killing Psychosis over a rolling window. Never reads or writes social status. | SB-004 | done |
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
| T-041 | Chat gradient from `#202020` to bright white by tier. Never absolute black, never hidden, truncated, delayed or blocked. | SB-020, SB-021 | done |
| T-042 | Chat colouring reads an immutable snapshot inside `AsyncChatEvent` and touches nothing else. | — | done |
| T-043 | Name hover: status, tier, Confidence, Psychosis, count of distinct contributors. | SB-022 | done |
| T-044 | Coexist with other prefix plugins: never overwrite display, list or custom name unconditionally. | SB-014 | done |
| T-045 | `/status [player]` profile output, including offline targets. | SB-005 | done |

**Gate:** nine tiers resolve across the full range; minimum-status messages are near-black, readable and never blocked.

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
| T-070 | Effect scheduler below a configurable status threshold, with an independent cooldown and per-session cap per effect. | SB-040, SB-043 | done |
| T-071 | Delivery to the affected player only. Nothing reaches other players, the real chat, or the server log. | SB-041 | done |
| T-072 | Speed III silverfish: no damage dealt or taken, no targeting, no loot, no XP, not persistent, removed on timer. | SB-042 | done |
| T-073 | Entity registry cleaned on despawn timer, quit, world change and disable. No entity survives any of them. | SB-042 | done |
| T-074 | Near-black short chat lines, creeper fuse sound, fake join and leave announcements — all private. | SB-040 | done |
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
| T-090 | Read an old PlayerStatus `config.yml`; write one `legacy_import` event per player, no actor. | — | todo |
| T-091 | Legacy events contribute nothing to Reputation Confidence. | SB-003 | todo |
| T-092 | Import is idempotent and reports what it did. | — | todo |

**Gate:** a real old configuration imports with no score loss and invents no evidence.

---

## P9 — Rating decay

| id | task | spec | status |
|---|---|---|---|
| T-110 | Age-weighted contribution of a reputation event to social status, curve configured in YAML. No event is ever deleted or rewritten. | SB-006 | todo |
| T-111 | Decay configuration is independent of Confidence's age weighting. Changing one must not change the other; a test proves it. | SB-006, SB-003 | todo |
| T-112 | Symmetric tier ladder: negative thresholds at `-5`, `-15`, `-30`, `-50`. Migrate an existing configuration without reclassifying anyone silently — report what moved. | SB-011a | todo |

**Gate:** an old negative event loses weight over a simulated year while the
event itself is still readable in the history; falling a tier costs the same
number of points as rising one.

---

## P10 — Rating history GUI and anonymity

| id | task | spec | status |
|---|---|---|---|
| T-120 | Double chest GUI from `/status [player]`: subject head, tier-coloured dye, green and red banners. | SB-080 | todo |
| T-121 | History grid: rater head, paper with the written reason, direction banner. Edge banners page forward and back. | SB-080 | todo |
| T-122 | The GUI opens from cache or a completed async load. No blocking read on the main thread, no inventory work off it. | SB-081 | todo |
| T-123 | A rating made in the GUI goes through the same honor path as the command: same cost, cooldown, cap and audit. | SB-081 | todo |
| T-124 | Rater names hidden by default; revealing one charges a configurable amount through Vault and is remembered per viewer. | SB-082 | todo |
| T-125 | Comments are length-bounded and rendered as plain text — no colour codes, formatting or click actions, whatever the rater typed. | SB-083 | todo |
| T-126 | Anonymity is cosmetic only: the SB-054 cap still counts per actor-target pair, and administrators and the audit trail still see everything. | SB-084 | todo |

**Gate:** a rating made in the GUI is indistinguishable in storage from one made
by command; a rater's name is hidden until paid for; a comment containing colour
codes renders as literal text.

---

## P11 — Hardening

| id | task | spec | status |
|---|---|---|---|
| T-100 | Walk every acceptance criterion in `docs/spec.md` §14 on a running Paper 26.3 server; record evidence per box. | §14 | todo |
| T-101 | Confirm no file or database I/O happens on the main thread. | §14 | todo |
| T-102 | Fresh install with no configuration, and install over an old PlayerStatus configuration. | §14 | todo |
| T-103 | Codex reviews the full release diff, not phase by phase. | — | todo |

**Gate:** every box in §14 ticked with evidence.

---

## P12 — Kill penalty and configurable sounds

Governed by `docs/decisions/0004-a-non-duel-kill-costs-status.md`. Reopens the
status half of T-062 and removes the last hardcoded sound.

| id | task | spec | status |
|---|---|---|---|
| T-130 | A kill outside a duel writes a system-authored reputation event: actor `SYSTEM`, cost `0`, message-key reason, YAML delta (default `-1`). Stored, derived and decayed like any other event. | SB-032 | todo |
| T-131 | The system event never raises Reputation Confidence, and Psychosis still never moves status. A test asserts both metrics move only by their own rule. | SB-034, SB-004 | todo |
| T-132 | One penalty per killer-victim pair per configurable cooldown, plus a configurable per-window cap on total automatic loss. | SB-035 | todo |
| T-133 | Penalty skipped inside a duel, when the killer cannot be identified, and in YAML-exempt worlds. Delta `0` disables the feature. | SB-036, SB-031 | todo |
| T-139 | An attack's duel context is decided when it lands, not when the victim dies: an arrow fired before consent that kills after the duel starts is an open-world kill, and one fired during a duel that lands after it ends is not. Carried over from the P5 review. | SB-031, SB-032 | todo |
| T-134 | The system event renders in the history GUI and in `/status history` like any other, with its reason translated from the message key. | SB-032, SB-080 | todo |
| T-135 | `sounds:` section in `config.yml`: one named slot per sound, each with key, volume, pitch and category. Every existing sound — starting with the creeper fuse in `AmbientEffectDispatcher` — reads its slot instead of a constant. | SB-090 | todo |
| T-136 | An empty slot plays nothing; an unrecognised key logs a warning naming the slot once and plays nothing. Neither ever throws or blocks the action the sound accompanied. | SB-090 | todo |
| T-137 | Slots are reloadable in-game with the rest of the configuration, and private sounds still reach only the affected player. | SB-091, SB-041, SB-062 | todo |
| T-138 | A slot may hold several layers, each with its own key, volume, pitch, category and tick delay; they play in order from one trigger. The single-mapping form still means one layer at delay `0`. | SB-092 | todo |

**Gate:** a non-duel kill lowers status once per pair cooldown, bounded by the
cap, never touching Confidence; every sound can be retuned or silenced from
`config.yml` with no restart and no recompile.
