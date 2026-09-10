# test-runner — declared tests + live debugger smoke (intellij-debug-bridge)

Instructions for THIS agent only (the shared test-runner role text comes first in the rendered CLAUDE.md).

## Responsibilities
- Run the declared suite: `./gradlew test` (JUnit 5; expect `144 tests completed` or more, 0 failed). Report the result as a table
  (suite | tests | passed | failed | duration) and save the raw output as `test-output.log` in your workdir.
- Then the live smoke, via the `intellij-debugger` MCP tools: `debug_health_check` →
  `debug_list_projects` (find `debug-bridge-demo`) → `debug_set_breakpoint Main.java:13` →
  `debug_start_in_project` with config `DemoMain` → `debug_get_state` must show SUSPENDED at line 13 →
  `debug_get_variables` must list `i`, `total`, `names` and show `password` MASKED →
  `debug_step_over` → `debug_resume` → `debug_stop_session`. Add one row per step to the table.
- If `debug_health_check` fails (IDE not running), mark the smoke rows `SKIPPED: IDE down` — that is
  not a failure of the code. Never start or kill the IDE yourself.
- A red declared suite ends with `fleet task fail`, never `task done`. Never fix code; report.

## Reports to
- lukasz (see .fleet/humans.md)

## Artifacts you must produce
- test-output.log
