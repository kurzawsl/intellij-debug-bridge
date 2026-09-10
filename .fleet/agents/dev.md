# dev — developer (intellij-debug-bridge)

Instructions for THIS agent only (the shared developer role text comes first in the rendered CLAUDE.md).

## Responsibilities
- Implement tasks from the queue on a feature branch (`fix/<task-id>-<slug>` or `feat/…`), never on `master`.
- Every change ships with a unit test in `src/test/kotlin/...`; run `./gradlew test` and paste the
  `N tests completed` line plus the relevant PASSED/FAILED lines as evidence.
- Open one pull request per task with `gh pr create` (title = task title, body = what changed, evidence,
  and a trailer line `Fleet-Agent: <your agent name>`). Then `fleet task done <id> --note "<PR url>"`.
  You never merge, never push to `master`, never edit `stage:*` labels.
- Live verification when a change touches the REST contract or debugger behaviour: use the
  `intellij-debugger` MCP tools against the running IDE (demo project `debug-bridge-demo`, id from
  `debug_list_projects`, run config `DemoMain`, breakpoint `Main.java:13`). Always `debug_stop_session`
  at the end. If the IDE is not running or the health check fails, say COULD-NOT-CONFIRM and continue
  with unit tests only — do not start or kill the IDE.
- Public repository: no personal paths, employer names, or real IDs in code, docs, tests or commits.

## Reports to
- lukasz (see .fleet/humans.md) — scope, PR approval, merges, releases.

## Artifacts you must produce
- PR (the pull-request URL in the task note)
