# AGENTS.md — intellij-debug-bridge

IntelliJ IDEA plugin exposing the debugger over a localhost REST API for AI assistants (Kotlin, Gradle, Ktor). Companion of intellij-debugger-mcp.

This file is the contract every agent (and every human) on this project works from.
It is read natively by Claude Code (via CLAUDE.md), Codex, Kimi and others.

## Read first

1. `docs/INDEX.md` — the table of contents. Read it BEFORE opening anything else, then
   go to the targeted file. Do not grep the whole repo to orient yourself.
2. `.fleet/humans.md` — who the humans are and what each of them decides on.
3. Your own charter: `.fleet/agents/<your-short-name>.md` (rendered into your CLAUDE.md).

## Build / test / run

- Toolchain: JDK 21, Gradle wrapper 8.5, Kotlin 2.1.0, IntelliJ Platform Gradle Plugin 2.2.1.
  Sources of truth: JDK in `jvmToolchain(21)` in build.gradle.kts and `.github/workflows/ci.yml`;
  wrapper version in `gradle/wrapper/gradle-wrapper.properties`;
  plugin versions in the `plugins` block of build.gradle.kts. Update this line when those change.
- Build plugin zip: `./gradlew buildPlugin` → `build/distributions/intellij-debug-bridge-<version>.zip`
- Test: `./gradlew test` (JUnit 5 + MockK; 144 tests, all must pass — a red test is a `task fail`, never "flaky")
- Build against the locally installed IDE instead of downloading one: `./gradlew buildPlugin -PlocalIdePath=/opt/intellij-idea-ultimate-edition`
- Run a sandbox IDE with the plugin: `./gradlew runIde`
- Smoke check of an installed plugin: `curl -s http://127.0.0.1:19999/health` (expect `{"status":"ok"}`)
- CI: `.github/workflows/ci.yml` runs build + test on every push/PR.

## Project facts agents must not get wrong

- Package root is `com.morpheus.debugbridge` (historical name, keep it; renaming breaks installed plugins).
- The REST server binds `127.0.0.1:19999` only and has NO auth — never add a non-loopback bind.
- Sensitive values (`password`, `token`, `secret`, `apiKey`, …) are masked in responses; keep the masking lists in `VariableExtractor.kt` and `ExpressionEvaluator.kt` in sync.
- V1 API (`/debug/*`) must stay backward compatible; new features go to V2 (`/v2/*`).
- `since-build`/`until-build` live in `build.gradle.kts` (patched into plugin.xml at build time).
- Public repo: no personal paths, no employer names, no real chat IDs in examples.

## Conventions

- Small, reviewable commits; one concern per PR.
- Never commit secrets. MCP servers are referenced by NAME in `.fleet/agents/*.json`; the
  definitions live in the operator's own catalog, outside this repo.
- Evidence over claims: a task is done when its artifacts exist and its checks ran.
- Escalate to the human named in `.fleet/humans.md` for anything that is theirs to decide.

## Team

Humans and their roles are listed in `.fleet/humans.md`. Agents are defined as charters in
`.fleet/agents/` (see `.fleet/agents/README.md`) and materialised with
`fleet project onboard`. `fleet team list` shows both side by side.
