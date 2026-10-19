# intellij-debug-bridge

[![CI](https://github.com/kurzawsl/intellij-debug-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/kurzawsl/intellij-debug-bridge/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

IntelliJ IDEA plugin that exposes the debugger over a local REST API so an AI assistant can
**see** where execution is stopped, **inspect** variables and the call stack, and **control**
the session (step, resume, breakpoints, evaluate). It is the companion of
[intellij-debugger-mcp](https://github.com/kurzawsl/intellij-debugger-mcp), an MCP server that
gives Claude (or any MCP client) these capabilities as tools.

```
┌──────────────┐   JDWP   ┌──────────────────────────────┐   REST :19999   ┌──────────────────────┐   MCP/stdio   ┌────────┐
│ Your JVM app │◄────────►│ IntelliJ debugger + this plugin│◄──────────────►│ intellij-debugger-mcp│◄────────────►│ Claude │
└──────────────┘          └──────────────────────────────┘                 └──────────────────────┘               └────────┘
```

## Features

- Debug state: current file/line/method, call stack, frame selection
- Variables at the current location, lazy expansion of objects and collections
- Expression evaluation in the suspended frame
- Execution control: step over / into / out, resume, pause, run-to-cursor
- Breakpoints: list, add, remove, toggle, clear
- Run configurations: list, create (Spring Boot, JS/TS, build tools), delete; start / stop a debug session
- Console output and IDE problems for the project
- Editor helpers: open/close file, list open files, go to line
- **V2 API**: several projects open in one IDE, each with multiple debug sessions
- Sensitive values (`password`, `token`, `secret`, `apiKey`, …) are masked in responses
- Server binds to `127.0.0.1` only, with input validation and rate limiting

## Requirements

- IntelliJ IDEA 2024.3 or newer (builds 243 – 262.*), Community or Ultimate
- JDK 21 to build

## Install

### From a release zip

1. Download `intellij-debug-bridge-<version>.zip` from the Releases page (or build it, see below).
2. In IntelliJ: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and pick the zip.
3. Restart the IDE. The plugin logs `Debug Bridge for AI - ACTIVE` in `idea.log` and listens on `http://127.0.0.1:19999`.

### Build from source

```bash
git clone https://github.com/kurzawsl/intellij-debug-bridge.git
cd intellij-debug-bridge
./gradlew buildPlugin            # → build/distributions/intellij-debug-bridge-<version>.zip
./gradlew test                   # unit tests
./gradlew runIde                 # sandbox IDE with the plugin loaded
```

To compile against an IDE already installed on your machine instead of downloading one:

```bash
./gradlew buildPlugin -PlocalIdePath=/opt/intellij-idea-ultimate-edition
```

## Quick check

```bash
curl -s http://127.0.0.1:19999/health
curl -s http://127.0.0.1:19999/debug/state
curl -s http://127.0.0.1:19999/v2/projects
```

A Postman collection with every endpoint is in [`postman/`](postman/).

## Testing

```bash
./gradlew test
```

The suite is plain JVM unit tests (JUnit 5 + MockK + AssertJ, Ktor test host for the REST
layer) — no IDE instance is required. Tests live in `src/test/kotlin/com/morpheus/debugbridge/`
and cover:

- **Services**: debug state machine (`DebugStateService`), session control (`DebugController`),
  breakpoints (`BreakpointManager`), variable extraction (`VariableExtractor`), expression
  evaluation (`ExpressionEvaluator`), and the central project/session registry
  (`AppRegistryService`: ID generation, registration lifecycle, indexes, last-touched tracking).
- **API layer**: V1 route shapes (`RestServerTest`) and the V2 multi-project routes
  (`V2RoutesTest`: discovery endpoints, session status mapping, session actions, error and
  rate-limit responses).
- **Models & utilities**: JSON model round-trips (`DebugModelsTest`), rate limiting
  (`RateLimiterTest`), session listener (`DebugSessionListenerTest`).

IntelliJ interfaces (`Project`, `XDebugSession`) are mocked with MockK; EDT-guarded logic is
tested by executing `invokeLater` blocks immediately.

## API

All endpoints are served from `http://127.0.0.1:19999`.

### V1 (single project, `/debug/*`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | liveness |
| GET | `/debug/state` | location, stack, session status |
| GET | `/debug/variables` | variables in the current frame |
| POST | `/debug/variables/expand` | expand an object / collection |
| GET | `/debug/frames`, POST `/debug/frames/select` | call stack, switch frame |
| POST | `/debug/evaluate` | evaluate an expression |
| POST | `/debug/step-over`, `/step-into`, `/step-out`, `/resume`, `/pause`, `/run-to-cursor` | execution control |
| GET/POST/DELETE | `/debug/breakpoints`, POST `/debug/breakpoints/toggle`, DELETE `/debug/breakpoints/all` | breakpoints |
| GET/POST/DELETE | `/debug/configurations` | run configurations |
| POST | `/debug/start`, `/debug/stop` | start / stop a debug session |
| GET | `/debug/modules` | project modules |
| GET/DELETE | `/debug/console` | console output |
| GET | `/debug/problems` | IDE problems |
| POST | `/debug/open-file`, `/debug/close-file`, `/debug/go-to-line`; GET `/debug/open-files` | editor helpers |

### V2 (multi-project, `/v2/*`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/v2/projects` | open projects (stable IDs = SHA-256 of path) |
| GET | `/v2/sessions` | all debug sessions across projects |
| GET | `/v2/sessions/{sessionId}/variables` | variables for a session |
| POST | `/v2/sessions/{sessionId}/step-over` … `/pause`, `/evaluate` | per-session control |
| GET | `/v2/projects/{projectId}/sessions`, `/configurations`, `/modules`, `/breakpoints` | per-project reads |
| POST/DELETE | `/v2/projects/{projectId}/configurations`, `/breakpoints` | per-project writes |
| POST | `/v2/projects/{projectId}/debug/start` | start a session in a project |

See [`docs/MULTI_PROJECT_ARCHITECTURE.md`](docs/MULTI_PROJECT_ARCHITECTURE.md) for the V2 design and
[`REQUIREMENTS.md`](REQUIREMENTS.md) for the full requirement list.

## Security notes

- Localhost only. The server never binds to a non-loopback interface, and CORS allows only `127.0.0.1`.
- There is **no authentication**. Any local process can drive your debugger while the IDE is open.
  Do not tunnel port 19999 off the machine.
- Values whose name matches a sensitive pattern are masked; everything else is returned in clear.

## Project layout

```
src/main/kotlin/com/morpheus/debugbridge/
├── DebugBridgeStartup.kt      # starts the REST server once per IDE
├── DebugSessionListener.kt    # tracks XDebugger sessions
├── api/RestServer.kt          # V1 routes (Ktor/Netty)
├── api/V2Routes.kt            # V2 multi-project routes
├── service/                   # debugger, breakpoints, variables, evaluation, files, console
└── util/                      # input validation, rate limiting
```

## License

MIT — see [LICENSE](LICENSE).
