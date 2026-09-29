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
| P4 Commands, permissions | — | next |
| P5 Duels | — | not started |
| P6 Ambient effects | — | not started |
| P7 Self-update | — | not started |
| P8 Legacy import | — | not started |
| P9 Hardening | — | not started |

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
| T-006 | Reduce the entry class to a skeleton: enable, disable, logging, no feature code. Remove `/pstatus evaluate`, the `eco` dispatch, the death item rewards and `/utils`. | §12 | done |
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
| T-020 | Domain tests: tier resolution across the full range including `0` and both extremes; cost with each multiplier step; cap expiry across a window boundary; Confidence over distinct versus repeated actors; status derived from an event list. | §13 | done |
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
| T-050 | `/status` dispatcher with `/pstatus` and `/reputation` aliases. Sender resolved before dispatch, so the console never reaches player-only code. | SB-065 | todo |
| T-051 | Centralised argument parsing. No subcommand calls `Integer.parseInt` on raw input. | SB-065 | todo |
| T-052 | Offline target resolution by UUID from `player_profile`, falling back to Bukkit's offline lookup. | SB-060, SB-065 | todo |
| T-053 | Declare `socialblueprint.*` nodes with explicit defaults. Keep existing `pstatus.*` grants working — children flow parent → child, so the legacy node is the parent or the check consults both (ARCHITECTURE §7). Verify against a real LuckPerms grant. Drop `pstatus.evaluate`. | SB-061 | todo |
| T-054 | Configurable action-to-node mapping, resolved at check time. Nodes are not invented at runtime. | SB-061 | todo |
| T-055 | `/status admin give|take|reset`: free, no cooldown, no cap, audited. Reset writes a compensating event, never a delete. | SB-058 | todo |
| T-056 | Audit rows for every administrative action: actor, operation, target, before, after, time. | SB-064 | todo |
| T-057 | Vault resolution at enable; disable with a clear reason if no provider. | SB-051 | todo |
| T-058 | Charge and event commit together, with refund on write failure. `EconomyResponse` is checked. | SB-057 | todo |

**Gate:** console runs every command without an exception; a name change does not detach a record; every admin action is audited.

---

## P5 — Duels

| id | task | spec | status |
|---|---|---|---|
| T-060 | Duel lifecycle: challenge, accept, deny, leave, expiry. 1v1 and group. | SB-030 | todo |
| T-061 | A kill inside an active duel affects neither status nor Psychosis. | SB-031 | todo |
| T-062 | A kill outside a duel raises Psychosis and never changes status. Removes the baseline deduction at `EventManager.java:24-29`. | SB-032, §12 | todo |
| T-063 | Configurable disconnect handling, distinguishing a combat log from a normal quit. | SB-033 | todo |

**Gate:** duel kill changes neither metric; open-world kill changes only Psychosis.

---

## P6 — Ambient effects

Parallel with P5.

| id | task | spec | status |
|---|---|---|---|
| T-070 | Effect scheduler below a configurable status threshold, with an independent cooldown and per-session cap per effect. | SB-040, SB-043 | todo |
| T-071 | Delivery to the affected player only. Nothing reaches other players, the real chat, or the server log. | SB-041 | todo |
| T-072 | Speed III silverfish: no damage dealt or taken, no targeting, no loot, no XP, not persistent, removed on timer. | SB-042 | todo |
| T-073 | Entity registry cleaned on despawn timer, quit, world change and disable. No entity survives any of them. | SB-042 | todo |
| T-074 | Near-black short chat lines, creeper fuse sound, fake join and leave announcements — all private. | SB-040 | todo |
| T-075 | `/status effects` per-player opt-out, persisted. Changes no metric and hides nothing from others. | SB-044 | todo |

**Gate:** effects are private; no entity leaks; opt-out works.

---

## P7 — Self-update

| id | task | spec | status |
|---|---|---|---|
| T-080 | `GET /repos/<owner>/<repo>/releases/latest` via `java.net.http`, on the executor, never on the main thread. Configurable repository and channel. | SB-073, SB-076 | todo |
| T-081 | `/status version`: running version versus latest release. | SB-070 | todo |
| T-082 | `/status update`: download the asset, verify its checksum, write to `plugins/update/`. A mismatch aborts and leaves the directory untouched. | SB-071, SB-074 | todo |
| T-083 | Report that a restart is required. Never restart the server. | SB-072 | todo |
| T-084 | Startup check on by default, automatic download off by default. | SB-075 | todo |
| T-085 | Every failure is a logged warning only. Never blocks startup, never delays a tick. | SB-073 | todo |

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

## P9 — Hardening

| id | task | spec | status |
|---|---|---|---|
| T-100 | Walk every acceptance criterion in `docs/spec.md` §13 on a running Paper 26.3 server; record evidence per box. | §13 | todo |
| T-101 | Confirm no file or database I/O happens on the main thread. | §13 | todo |
| T-102 | Fresh install with no configuration, and install over an old PlayerStatus configuration. | §13 | todo |
| T-103 | Codex reviews the full release diff, not phase by phase. | — | todo |

**Gate:** every box in §13 ticked with evidence.
