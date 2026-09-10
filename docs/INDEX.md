# docs/INDEX.md — intellij-debug-bridge documentation index

Read this first. One line per document: what it is and when to open it.

| Document | What it covers | Open it when |
|---|---|---|
| `AGENTS.md` | the agent/human contract: build, test, conventions | always, once |
| `.fleet/humans.md` | who decides what | before escalating |
| `.fleet/agents/README.md` | the agent charter format | adding or changing an agent |
| `README.md` | what the plugin does, install, API tables (V1/V2), security notes | orienting; before touching an endpoint |
| `REQUIREMENTS.md` | numbered functional requirements (FR-x) with priorities | deciding whether a change is in scope |
| `docs/MULTI_PROJECT_ARCHITECTURE.md` | V2 multi-project design: project IDs, session IDs, routing | anything under `/v2/*` |
| `docs/BUILD-BRIEF.md` | the original build brief (architecture sketch, endpoint list) — historical | background only |
| `DEBUGGING_GUIDE.md` | how to debug the plugin itself (sandbox IDE, logs, tests) | when a change cannot be verified by unit tests |
| `MCP_UPGRADE_PROMPT.md` | how the MCP client was upgraded to the V2 API — historical | changing the V1/V2 contract |
| `postman/` | Postman collection + local env covering every endpoint | manual endpoint checks |
| `.github/workflows/ci.yml` | CI: build + test on push/PR | CI red, or adding a check |
