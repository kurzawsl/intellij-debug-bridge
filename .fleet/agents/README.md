# Agent charters

One agent = one pair of files in this directory, named by the agent's SHORT name:

- `<short>.json` — the machine half, schema `fleet/agent-charter/v1`:

  ```json
  {
    "schema": "fleet/agent-charter/v1",
    "short": "dev",
    "role": "developer",            // must exist in the fleet's roles/ directory
    "engine": "claude",             // "claude" | "codex" | "kimi" (default claude)
    "model": "",                    // empty = the project's default_model
    "sandbox": null,                // codex only: {"mode": "workspace-write"|"danger-full-access",
                                    //   "network": false, "writable_roots": ["/abs/path"]} — extra roots are
                                    //   ADDED to the defaults (workdir, the project's tasks/ and intents/)
    "mcp": ["github"],              // MCP server NAMES only — definitions stay in the operator's catalog
    "add_dirs": [],                 // extra directories the agent may touch
    "reports_to": "owner",          // a human (from humans.md) or another agent's short name
    "artifacts": ["REVIEW.md"],     // files this agent must produce
    "heartbeat": "30m",             // wake cadence like "30m" | "2h" | null
    "budget_usd_week": null         // number | null
  }
  ```

- `<short>.md` — the human half: the instructions for THIS agent only, appended to its
  rendered CLAUDE.md after the shared role text. Edit freely; every render picks it up.

Scaffold a pair with `fleet agent charter new <short> --role <role> [--engine E]
[--reports-to X] [--artifact F]...`, then materialise the team with
`fleet project onboard [--dry-run]`. The runtime under ~/.agent-fleet is DERIVED from
these files: edit here, commit, onboard — never the other way round.
