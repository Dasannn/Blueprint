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

Dispatch through `.agent/dispatch-agy.cmd <prompt> <worktree>` and
`.agent/dispatch-codex.cmd <prompt> <dir>`, never the CLIs directly. They carry
hard-won details:

* `agy -p` takes the **next token** as its prompt, so it must come last.
  `-p --model ...` silently sends the literal string `"--model"`.
* Codex needs `--skip-git-repo-check` outside a repository, and its
  `workspace-write` sandbox blocks outbound sockets unless
  `sandbox_workspace_write.network_access=true` is set. It also cannot run
  `javac` on this machine — the sandbox denies closing cached jars — so **Codex
  reads and finds, Antigravity builds and runs**.
* The wrappers retry on a usage limit with backoff and append the raw failure
  to `.agent/limit-samples.log`, because neither CLI's exhaustion wording is
  known yet.
* `agy` prints nothing until it finishes, so its output file is no liveness
  signal. Watch the process and the worktree's file mtimes instead.
* `agy` will background a long build and then idle waiting for it, until its
  own 30 minute timeout cuts it off with no report. Briefs that end in a build
  must say to run it in the foreground.

## Progress

`docs/tasks.md` is the single record of where the project is: phase table at
the top, per-task status column below. Update it as work moves, not at the end.
Do not create a second progress file — a `MEMORY.md`, a status report, a
summary of what was just finished. The source-of-truth list above has five
documents and a sixth would only compete with them.

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
