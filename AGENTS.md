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

Claude orchestrates, owns git and runs the build. Antigravity writes
production code. Codex reviews it and issues corrections. One task per
worktree.

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
  reads and finds, Antigravity writes, Claude builds**.
* The wrappers retry on a usage limit with backoff and append the raw failure
  to `.agent/limit-samples.log`, because neither CLI's exhaustion wording is
  known yet.
* `agy` prints nothing until it finishes, so its output file is no liveness
  signal. Watch the process and the worktree's file mtimes instead.
* **Claude runs the build. Agents do not.** `agy` backgrounds a long build and
  then idles waiting for it until its own 30 minute timeout kills the round
  with no report — twice, including once after a brief told it to run the build
  in the foreground. Codex cannot compile here at all. So briefs say *do not
  run Maven*, and Claude runs `mvnw clean verify` and feeds the real compiler
  output back. A cycle then costs minutes instead of half an hour.
* Because it never compiles, `agy` invents method signatures that look right:
  `oldValue()` for `before()`, a `findRecentAsync` that does not exist, an
  accessor a decision record had just removed. **Before calling a method, grep
  its declaration.** A brief that introduces a new type should quote its real
  signature.
* Trivial compile errors — a missing import, the wrong constructor overload, a
  lambda capturing a branch-assigned local — are faster for Claude to fix than
  to send back. Only structural errors earn a round trip.
* Codex writes its report to stdout as well as the file it was asked for, and
  sometimes only to stdout. Check the task output before concluding it produced
  nothing.
* Build with `-Dmaven.compiler.fork=true`. The in-process javac on this
  machine crashes with `NullPointerException` in
  `UnsharedNameTable.fromValidUtf` and reports only "Fatal error compiling",
  hiding every real error behind it. Forking javac into its own process prints
  them normally. This cost a round of blind guessing once.

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
