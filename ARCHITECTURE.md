# Architecture — SocialBlueprint

Subordinate to `docs/constitution.md` and `docs/spec.md`. Describes *how* the
plugin is built. Requirement ids `SB-nnn` refer to the specification.

## 1. Shape of the project

One Maven module producing one jar. No multi-module split: this is a single
plugin, and a module boundary that nothing crosses is cost without benefit.

```
pom.xml
mvnw, mvnw.cmd, .mvn/          Maven wrapper (Maven is not installed locally)
src/main/java/com/dasannn/socialblueprint/
src/main/resources/            plugin.yml, config.yml, messages.yml
src/test/java/                 domain tests, no server required
```

Two corrections to the baseline are structural:

- Resources move from `src/main/java` to `src/main/resources`. The baseline made
  it work with an explicit Maven `<resource>` block (`pom.xml:31-41`), which is
  fragile the moment anything else builds the project.
- The package moves from `com.gmail.crizardevelop.playerstatus` to
  `com.dasannn.socialblueprint`, and the entry class from `main` to
  `SocialBlueprintPlugin`. `PrefixManager` is deleted outright — it is entirely
  commented out and wrongly extends `JavaPlugin`.

## 2. Build

```xml
<properties>
  <maven.compiler.release>25</maven.compiler.release>
</properties>
```

- `io.papermc.paper:paper-api:26.3.build.135-beta`, scope `provided`, from
  `https://repo.papermc.io/repository/maven-public/`. The build number is
  pinned; bumping it is a deliberate commit.
- Vault API, scope `provided`.
- `org.xerial:sqlite-jdbc` declared in `plugin.yml` under `libraries:`, so Paper
  downloads it at runtime. Not shaded: shading a JDBC driver into every plugin
  jar is wasted bytes and a relocation hazard.
- No other runtime dependencies. HTTP uses `java.net.http.HttpClient` from the
  JDK; JSON parsing for the GitHub release check is a few lines against one
  known response shape, not a library.

## 3. Layers

Dependencies point downward only. Nothing below the platform layer imports
Bukkit.

```
platform/   Bukkit entry point, listeners, commands, scheduling, Adventure
feature/    duels, effects, honor, updater, profile
domain/     status, tiers, confidence, psychosis, honor rules — pure Java
storage/    SQLite, repositories, migrations
config/     typed configuration, live reload, in-game editing
```

**`domain/` imports nothing from Bukkit.** That is the rule that makes the
project testable: tier resolution, cost calculation, the per-pair cap, the
Confidence formula and the Psychosis window are all pure functions over plain
data, and they are where every bug in the baseline lived.

## 4. Data model

Event-sourced, per constitution §2.5. SQLite, one file in the plugin's data
folder.

| Table | Purpose |
|---|---|
| `player_profile` | uuid, last known name, opt-out flag, created/updated |
| `reputation_event` | id, actor uuid, target uuid, delta, kind, cost, reason, created_at |
| `psychosis_event` | id, killer uuid, victim uuid, context (duel/open), created_at |
| `honor_allowance` | actor uuid, target uuid, sign, count, window_start |
| `duel` | id, state, created_at, ended_at |
| `duel_participant` | duel id, uuid, side |
| `audit_event` | id, actor, operation, target, before, after, created_at |
| `schema_version` | single row, drives migrations |

Rules:

- **UUID is the only identity** (SB-060). Names are stored for display and are
  never a key. The baseline keyed votes by name while initialising by UUID
  (`main.java:93-102` versus `369-372`), which is why its vote removal never
  found anything.
- Aggregate status is **derived** from `reputation_event`, never stored as the
  authoritative value. A cached aggregate is held in memory for fast lookup and
  invalidated on write; it is a cache, and it can always be rebuilt.
- An administrative reset writes a compensating `reputation_event`, never a
  delete (SB-058).
- The imported legacy score is written as **one** `reputation_event` per player,
  of kind `legacy_import`, with no actor. It is visibly not real evidence, so it
  contributes nothing to Reputation Confidence.

Migrations are numbered SQL applied in order against `schema_version`. There is
no ORM.

## 5. Threading

Paper's rules, taken seriously, because the baseline broke them on every code
path (`saveConfig()` on the main thread at `main.java:144,218-278,341-375`).

- **All database access runs on a single-threaded executor.** One thread, so
  writes serialise and SQLite never sees concurrent writers. No connection pool
  — one connection to one local file does not need one.
- **All Bukkit API access runs on the main thread.** A feature that reads from
  the database and then touches a player hops: main → executor → main, via the
  Paper scheduler.
- `AsyncChatEvent` is async. The tier prefix and Psychosis chat corruption
  (SB-094, SB-095) read immutable profile and configuration snapshots. Compute
  the corrupted text once per message and reuse it for every viewer; rendering
  must not reroll it. This path performs no database or Bukkit access.
- Psychosis ambient effects (SB-096 through SB-099) reuse the main-thread
  scheduler, per-player cooldowns and session caps, managed-entity registry and
  configurable layered sounds. Phantom mobs are packet-only fakes, removed
  immediately and cleaned on quit, world change and disable; no real server
  entity is spawned. An interrupted effect must leave no fake behind. Quiet
  intervals include delayed sound layers, so overlapping layers cannot turn
  episodes into a constant effect.

## 6. Configuration

Two files: `config.yml` for behaviour, `messages.yml` for text.

- Loaded once into **typed, immutable records**. No `getString()` scattered
  through feature code — that is how the baseline ended up dereferencing a
  missing `tier-5` key at `main.java:164`.
- **Validated on load.** A malformed tier ladder, a missing required key or a
  negative cooldown fails the enable with a message naming the offending key.
  Never a silent zero.
- Reload replaces the immutable snapshot atomically. Nothing caches derived
  values from it across a reload, which is what made the baseline's static
  prefix fields (`main.java:16-50`) immune to config edits (SB-013).
- In-game edits (SB-062) write through the same validation, persist to disk, and
  publish a new snapshot.

Colours: one interpretation only, Adventure's legacy serializer with `&` as the
character and hex support. Strings are never also parsed as MiniMessage —
supporting both makes `&` sequences ambiguous.

## 7. Permissions

Checks go through `Permissible#hasPermission`, so LuckPerms grants apply with no
LuckPerms dependency and no LuckPerms API call.

Nodes are `socialblueprint.*`, declared in `plugin.yml` with explicit defaults.
`pstatus.evaluate` is dropped along with the command it guarded.

The legacy `pstatus.*` nodes must keep working for servers that already granted
them. **Children flow parent → child**, so declaring the old nodes as children
of the new ones is backwards: it would make a grant of the *new* node imply the
old one, which is not what anyone needs. The old node must be the parent, or the
check must consult both. `docs/reference/paper-26.3-notes.md` §9 has the
mechanics; the direction is verified against a real LuckPerms grant before P4
closes.

Node *strings* being configurable (SB-061) means the mapping from an action to a
node is read from configuration at check time; it does not mean nodes are
invented at runtime, which would silently break every existing grant.

## 8. Commands

One dispatcher on `/status`, with `/pstatus` and `/reputation` as aliases. Each
subcommand is a small class declaring its permission, its argument shape and
whether it accepts a console sender.

The dispatcher resolves the sender **before** the subcommand runs, so a console
sender never reaches code that assumes a player — the unconditional cast at
`Commands.java:21-22` crashes the console on every command today. Argument
parsing is centralised: no subcommand calls `Integer.parseInt` on raw input.

Offline targets resolve by UUID from `player_profile`, falling back to Bukkit's
offline player lookup. Online-only targeting (`Commands.java:47,78,92`) is a
defect, not a design.

## 9. Economy

Vault is a hard dependency (SB-051). The provider is resolved once at enable; if
there is none, the plugin logs why and disables itself rather than running
half-functional.

Charging and event-writing commit together (SB-057): withdraw, then write the
event in the same transaction, and refund if the write fails. Vault's
`EconomyResponse` is checked — the baseline dispatched `eco give` as a console
command string and never looked at the result (`main.java:440-495`).

## 10. Self-update

- `GET https://api.github.com/repos/<owner>/<repo>/releases/latest`, on the
  database executor, never on the main thread (SB-073). Unauthenticated; the
  rate limit is far above one check per startup.
- Compares the release tag against the version in `plugin.yml`.
- `/status update` downloads the jar asset, verifies its checksum, and writes it
  into `plugins/update/` — Bukkit's own mechanism, which swaps the jar on the
  next restart. **The running jar is never touched** (SB-071): replacing a
  loaded jar in place corrupts the class loader.
- Every failure — offline, rate limited, malformed response, checksum mismatch —
  is a logged warning and nothing else. The update path can never prevent a
  startup or delay a tick.

## 11. Testing

`domain/` is pure Java, so its rules are covered by ordinary JUnit 5 tests with
no server: tier resolution across the full range including `0` and both
extremes, cost with the progressive multiplier, the per-pair cap and its window
expiry, Confidence over distinct actors, Psychosis windowing, and status derived
from an event list.

`storage/` is tested against an in-memory SQLite database, including the
migration chain.

Platform wiring — listeners, commands, scheduling — is verified by running a
real Paper 26.3 server. No mock-server framework is introduced; keeping the
logic out of the Bukkit layer is what makes that acceptable.

## 12. Open items

- The Paper build number `135-beta` is on the BETA channel. When 26.3 reaches a
  stable channel, bumping it is a one-line commit.
- `com.dasannn.socialblueprint` as the package and `SocialBlueprintPlugin` as
  the entry class are proposals. They change the plugin's identity on disk, so
  they are the product owner's call.
