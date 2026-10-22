# ADR 0007 — Agents get sources from the prepare step and write fixes as search/replace edits

- Status: Accepted
- Date: 2026-10-21
- Settles: how the fix writer reads code without cloning (tech doc section 8 lists `read_file`, `search_code`)

## Context

The fix writer and critic need the test's source code. agent-service runs outside the runner namespace and must
never run or fetch repository code itself (tech doc section 11). Models are also unreliable at writing unified
diffs: hunk headers and context lines come out wrong, and `git apply` rejects them.

## Decision

- An experiment's prepare Job runs `publisher --sources`. It finds each planned test's class file plus the project
  classes it imports and the class it most likely tests (`FooTest` → `Foo`), and publishes them on
  `sources.exported` (at most 8 files, 300 KB). The orchestrator stores them on the experiment and adds them to
  `experiment.completed`. The agent keeps them once in its state.
- The fix writer answers with search/replace edits on those files. Each search must appear exactly once, so every
  edit is grounded in real code. agent-service builds the unified diff with `difflib`, which always applies.
- Before the critic sees a fix, a regex check refuses `@Disabled`/`@Ignore`, retries, added sleeps and deleted
  assertions without spending a model call.
- A verification is an experiment with a patch: `verification.requested` carries the same payload plus `patch`,
  prepare applies it with `git apply` (exit 3 if it does not apply), and the answer comes on `fix.verified`. It
  reruns the cited experiment that failed most, 100 times; any failure or missing run means it did not pass.

## Consequences

- No source tool calls: the fix writer sees only the exported files. A fix that needs another file fails and is
  reported as `FIX_FAILED`; widening the export (more import levels, same-package references) is the upgrade.
- Sources arrive only with the first experiment, so the investigator's first decision has none.
- Verification runs are Jobs like any other: 100 runs at 4 in parallel take several minutes on a laptop.
- Worst case per finding is about 13 large-model calls (5 investigator, 4 fix writer, 4 critic), within the free
  tier's 20 a day for one finding, not for several.
