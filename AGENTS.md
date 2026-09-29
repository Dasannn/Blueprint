# Agent Rules

Multiple AI agents may work on this repository concurrently.

## General

* Read relevant project documentation before working.
* Never work directly on `main` unless explicitly instructed.
* One task per worktree. Never modify another agent's worktree.
* Do not merge into `main`.
* Follow documented requirements and architecture.
* Do not invent requirements or change architecture without approval.
* If missing, conflicting, or ambiguous information materially affects implementation, ask before proceeding.

## Source of Truth

Priority order:

1. `docs/constitution.md`
2. `docs/spec.md`
3. `ARCHITECTURE.md`
4. `docs/plan.md`
5. `docs/tasks.md`

## Documentation

Keep `docs/` organized:

```text
docs/
├── constitution.md
├── spec.md
├── plan.md
├── tasks.md
├── decisions/
├── guides/
└── reference/
```

* Do not add miscellaneous files directly to `docs/`.
* Create documentation only when it has durable project value.
* Update existing documentation instead of creating duplicates.
* Do not create documentation merely to report completed work.

## Repository Hygiene

* Only commit product code, tests, tooling, configuration, and durable documentation.
* Temporary agent work belongs in `.agent/` and must never be committed.
* Do not commit generated, debug, cache, build, scratch, or local-only artifacts.
* Before handoff, verify the task introduced no unintended files.

## Toolchain

Target platform is fixed in `docs/constitution.md` §3. Practical notes:

* Minecraft **26.3** is a real release (2026-09-15). Mojang moved to year-based
  version ids; it is not a typo for `1.21.x`.
* Paper 26.3 is on the **BETA** channel. Maven artifact:
  `io.papermc.paper:paper-api:26.3.build.<n>-beta` from
  `https://repo.papermc.io/repository/maven-public/`. Pin the build in
  `pom.xml`; bump it deliberately.
* Paper build metadata comes from `https://fill.papermc.io/v3/...`. The
  `api.papermc.io/v2` endpoints are sunset and return an error.
* **Java 25**, required by Paper from 26.1 onward.
* Maven is not installed locally. Use the Maven wrapper (`mvnw`).

## Agent Pipeline

Claude orchestrates and owns git. Antigravity writes production code. Codex
reviews it and issues corrections. One task per worktree.

```bash
# Antigravity
agy -p "<prompt>" --model gemini-3.8-flash-high

# Codex
codex exec -m gpt-6-sol -c model_reasoning_effort="medium" --sandbox workspace-write < prompt.md
```

Task briefs go in `.agent/prompts/`, reports in `.agent/reports/`. Neither is
committed. A report with durable value is moved into `docs/reference/` instead.

Commits and pull requests carry **no** AI attribution: no `Co-Authored-By`, no
"generated with" footer.

## New Projects

If the core project documentation does not exist, do not implement application code.

First inspect the repository and clarify the product with the user.

Create in order:

1. `docs/constitution.md`
2. `docs/spec.md`
3. `ARCHITECTURE.md`
4. `docs/plan.md`
5. `docs/tasks.md`

For each stage:

* clarify material ambiguities
* propose the document
* revise from feedback
* continue only after approval

Do not implement application code until planning is approved.
