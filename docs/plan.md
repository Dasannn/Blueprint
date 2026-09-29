# Plan — Release 1

Subordinate to `docs/constitution.md`, `docs/spec.md` and `ARCHITECTURE.md`.
Describes the order of work and why that order. The individual units of work are
in `docs/tasks.md`.

## Scope

Release 1 delivers: a plugin that enables cleanly, the rewritten core (status,
tiers, prefixes, chat), duels, the private ambient effects, and self-update from
GitHub.

The honor economy ships **storage and domain rules only** — the tables, the cost
calculation, the per-pair cap, the Vault wiring — without the player-facing
Trust/Distrust flow, which needs the confirmation GUI of release 2. Reputation
Confidence is likewise computed and stored, but not surfaced. Building the
foundations now and the surfaces later avoids a second migration.

Explicitly not in release 1: public comments and the Bobba filter, the profile
GUI, vouching, trade warnings and external integrations.

## Order and rationale

Each phase is one git worktree, one Antigravity task, one Codex review. A phase
is done when its gate passes; phases do not overlap unless marked parallel.

### P0 — Build foundation

Retarget the build to Java 25 and pinned Paper 26.3, add the Maven wrapper, move
resources out of the java source root, rename the package, delete
`PrefixManager`, and reduce the entry class to a skeleton that enables and
disables cleanly.

*First because* nothing else can be compiled or tested until the build targets
the right platform, and because the baseline does not survive a fresh enable at
all. Everything downstream needs a green starting point.

**Gate:** a real Paper 26.3 server starts with the jar present, with no
configuration file, and logs a clean enable and disable.

### P1 — Storage and domain

SQLite, the migration chain, the repositories, and the pure domain rules: tier
resolution, status derivation from events, the honor cost and cap, the
Confidence formula, the Psychosis window.

*Second because* it is the only phase with no Bukkit dependency, so it is fully
testable before any server is involved, and every later phase reads from it.

**Gate:** domain and storage tests pass, including tier resolution at `0` and at
both extremes, cap expiry across a window boundary, and the full migration chain
against an in-memory database.

### P2 — Configuration

Typed immutable config, `config.yml` and `messages.yml`, load-time validation,
atomic reload, and the in-game editing command.

*Third because* every remaining phase reads configuration, and because
validation has to exist before features start depending on keys. A missing key
producing a silent zero is the defect that breaks the baseline.

**Gate:** a malformed tier ladder fails the enable naming the offending key; an
in-game colour edit takes effect with no restart.

### P3 — Status, tiers, prefixes and chat

Wire the domain to the server: resolve prefixes per lookup, render the chat
gradient, the name hover, and the profile display. Coexist with other prefix
plugins rather than overwriting display names unconditionally.

**Gate:** the nine tiers resolve correctly across the full range; a minimum
status player's messages are near-black and still fully readable and never
blocked.

### P4 — Commands and permissions

The `/status` dispatcher with aliases, centralised argument parsing, sender
resolution before dispatch, offline target support, the `socialblueprint.*`
nodes with `pstatus.*` as children, and the audited admin operations.

**Gate:** the console runs every command without an exception; a player who
changes name keeps their record; every admin action leaves an audit row.

### P5 and P6 — Duels, and ambient effects (parallel)

Independent of each other: duels touch combat events and Psychosis, effects
touch scheduling and per-player packets. Both depend on P1 through P4.

**Gate P5:** a duel kill changes neither status nor Psychosis; a kill outside a
duel changes only Psychosis.

**Gate P6:** effects reach the affected player only; no entity survives a quit,
a world change or a restart; the opt-out works.

### P7 — Self-update

Version check and download, off the main thread, checksum-verified, written to
`plugins/update/`.

*Late because* it is the phase most independent of the rest, and the one whose
failure modes matter least during development.

**Gate:** correct report with GitHub reachable and unreachable; a verified jar
lands in `plugins/update/` and the server picks it up on restart; a corrupted
download leaves the directory untouched.

### P8 — Legacy import

Read an old PlayerStatus `config.yml` and write one `legacy_import` reputation
event per player. Contributes no Confidence.

*Last because* it needs the final schema, and because it is the phase most
likely to be revised once the rest is real.

**Gate:** a real old configuration imports without loss of scores and without
inventing evidence.

### P9 — Hardening

Walk every acceptance criterion in `docs/spec.md` §13 against a running Paper
26.3 server. Codex reviews the whole diff, not phase by phase.

**Gate:** every box in §13 ticked, with evidence.

## Working agreement

- One worktree per phase, branch named `feat/p<n>-<slug>`.
- Antigravity implements against the phase's tasks; Codex reviews and issues
  corrections; Claude commits and pushes.
- A phase that reveals a specification gap stops and produces a decision file
  under `docs/decisions/`, rather than an agent choosing for itself.
- No phase merges into `main`.

## Risks

- **Paper 26.3 is on the BETA channel.** The pinned build may be superseded or
  may carry API changes mid-project. Mitigated by pinning and by keeping the
  Bukkit surface thin.
- **No mock server.** Platform wiring is only verified on a real server, so the
  P0 gate — having that server running — is load-bearing for every later gate.
- **Ambient effects are the easiest place to leak entities.** The explicit
  registry is a requirement, not an optimisation.
- **The package rename changes the plugin's data folder on disk.** Any existing
  production install needs P8 to run before its old data is meaningful.
