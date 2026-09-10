# MCP Server Upgrade: V2 Multi-Project Support

## Overview

The IntelliJ Debug Bridge plugin has been upgraded to V2 API with full multi-project support. The MCP server at `<path-to>/intellij-debugger-mcp/` needs to be upgraded to use the new API.

## Current V1 API (still works for backward compatibility)

```
GET  /debug/state              # Returns state of "current" session
GET  /debug/variables          # Variables from current session
POST /debug/step-over          # Step over in current session
POST /debug/step-into          # Step into in current session
POST /debug/step-out           # Step out in current session
POST /debug/resume             # Resume current session
POST /debug/pause              # Pause current session
GET  /debug/breakpoints        # List breakpoints (all projects)
POST /debug/breakpoints        # Add breakpoint
DELETE /debug/breakpoints      # Remove breakpoint
```

## New V2 API Endpoints

### Discovery (NEW)

```
GET /v2/projects               # List ALL open projects with IDs
GET /v2/sessions               # List ALL active debug sessions with IDs
```

Response examples:
```json
// GET /v2/projects
{
  "projects": [
    {
      "id": "a1b2c3d4...",
      "name": "payment-service",
      "path": "/Users/dev/workspace/payment-service",
      "sessionCount": 2
    },
    {
      "id": "e5f6g7h8...",
      "name": "claude-assistant",
      "path": "/Users/dev/workspace/claude-assistant",
      "sessionCount": 1
    }
  ]
}

// GET /v2/sessions
{
  "sessions": [
    {
      "id": "session-uuid-1",
      "name": "AccountingApplication",
      "projectId": "a1b2c3d4...",
      "projectName": "payment-service",
      "status": "SUSPENDED",
      "location": { "file": "Main.java", "line": 42 }
    }
  ]
}
```

### Session-Scoped Operations (NEW)

```
GET    /v2/sessions/{sessionId}              # Get session details with stack trace
GET    /v2/sessions/{sessionId}/variables    # Get variables for THIS session
POST   /v2/sessions/{sessionId}/step-over    # Step over in THIS session
POST   /v2/sessions/{sessionId}/step-into    # Step into in THIS session
POST   /v2/sessions/{sessionId}/step-out     # Step out of THIS session
POST   /v2/sessions/{sessionId}/resume       # Resume THIS session
POST   /v2/sessions/{sessionId}/pause        # Pause THIS session
POST   /v2/sessions/{sessionId}/evaluate     # Evaluate expression in THIS session
DELETE /v2/sessions/{sessionId}              # Stop THIS session
```

### Project-Scoped Operations (NEW)

```
GET    /v2/projects/{projectId}              # Get project details
GET    /v2/projects/{projectId}/sessions     # List sessions for THIS project
GET    /v2/projects/{projectId}/configurations  # Run configurations for THIS project
GET    /v2/projects/{projectId}/modules      # Modules in THIS project
GET    /v2/projects/{projectId}/breakpoints  # Breakpoints in THIS project
POST   /v2/projects/{projectId}/breakpoints  # Add breakpoint to THIS project
DELETE /v2/projects/{projectId}/breakpoints  # Remove breakpoint from THIS project
POST   /v2/projects/{projectId}/debug/start  # Start debug in THIS project
```

## New Response Models

### SessionDetailResponse
```typescript
interface SessionDetailResponse {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  status: 'RUNNING' | 'SUSPENDED' | 'TERMINATED' | 'NO_SESSION';
  location?: {
    file: string;
    filePath: string;
    line: number;
    method?: string;
    className?: string;
  };
  stackTrace?: StackFrame[];
}
```

### SessionActionResponse (for control operations)
```typescript
interface SessionActionResponse {
  success: boolean;
  message: string;
  sessionId: string;
  session?: SessionDetailResponse;
}
```

### ErrorResponse (improved)
```typescript
interface ErrorResponse {
  error: string;  // e.g., "SESSION_NOT_FOUND", "PROJECT_NOT_FOUND"
  message: string;
  availableSessions?: string[];  // Hints for what sessions exist
  availableProjects?: string[];  // Hints for what projects exist
}
```

## MCP Tools to Add

### 1. Discovery Tools (HIGH PRIORITY)

```typescript
// List all projects
server.tool(
  'debug_list_projects',
  'List all open IntelliJ projects with their IDs. Use this to find the projectId for other operations.',
  {},
  async () => {
    const projects = await client.getProjects();
    // Format and return...
  }
);

// List all sessions
server.tool(
  'debug_list_sessions',
  'List all active debug sessions across all projects. Returns sessionId for each session.',
  {},
  async () => {
    const sessions = await client.getSessions();
    // Format and return...
  }
);
```

### 2. Session-Scoped Control Tools (HIGH PRIORITY)

Add `sessionId` parameter to all control tools:

```typescript
server.tool(
  'debug_step_over_v2',
  'Step over in a specific debug session.',
  {
    sessionId: z.string().describe('Session ID from debug_list_sessions'),
  },
  async ({ sessionId }) => {
    const result = await client.stepOverSession(sessionId);
    // ...
  }
);
```

### 3. Project-Scoped Tools

```typescript
server.tool(
  'debug_start_in_project',
  'Start debugging in a specific project.',
  {
    projectId: z.string().describe('Project ID from debug_list_projects'),
    config: z.string().optional().describe('Run configuration name'),
  },
  async ({ projectId, config }) => {
    const result = await client.startDebugInProject(projectId, config);
    // Returns new sessionId
  }
);
```

## intellij-client.ts Updates

Add new methods to the client class:

```typescript
// Discovery
async getProjects(): Promise<ProjectsResponse> {
  return this.fetch<ProjectsResponse>('/v2/projects');
}

async getSessions(): Promise<SessionsResponse> {
  return this.fetch<SessionsResponse>('/v2/sessions');
}

// Session-scoped
async getSessionDetails(sessionId: string): Promise<SessionDetailResponse> {
  return this.fetch<SessionDetailResponse>(`/v2/sessions/${sessionId}`);
}

async getVariablesForSession(sessionId: string): Promise<VariablesResponse> {
  return this.fetch<VariablesResponse>(`/v2/sessions/${sessionId}/variables`);
}

async stepOverSession(sessionId: string): Promise<SessionActionResponse> {
  return this.fetch<SessionActionResponse>(`/v2/sessions/${sessionId}/step-over`, { method: 'POST' });
}

async stepIntoSession(sessionId: string): Promise<SessionActionResponse> {
  return this.fetch<SessionActionResponse>(`/v2/sessions/${sessionId}/step-into`, { method: 'POST' });
}

async stepOutSession(sessionId: string): Promise<SessionActionResponse> {
  return this.fetch<SessionActionResponse>(`/v2/sessions/${sessionId}/step-out`, { method: 'POST' });
}

async resumeSession(sessionId: string): Promise<SessionActionResponse> {
  return this.fetch<SessionActionResponse>(`/v2/sessions/${sessionId}/resume`, { method: 'POST' });
}

async pauseSession(sessionId: string): Promise<SessionActionResponse> {
  return this.fetch<SessionActionResponse>(`/v2/sessions/${sessionId}/pause`, { method: 'POST' });
}

async stopSession(sessionId: string): Promise<SessionActionResponse> {
  return this.fetch<SessionActionResponse>(`/v2/sessions/${sessionId}`, { method: 'DELETE' });
}

async evaluateInSession(sessionId: string, expression: string): Promise<EvaluateResponse> {
  return this.fetch<EvaluateResponse>(`/v2/sessions/${sessionId}/evaluate`, {
    method: 'POST',
    body: JSON.stringify({ expression }),
  });
}

// Project-scoped
async getProjectDetails(projectId: string): Promise<ProjectInfo> {
  return this.fetch<ProjectInfo>(`/v2/projects/${projectId}`);
}

async getProjectSessions(projectId: string): Promise<SessionsResponse> {
  return this.fetch<SessionsResponse>(`/v2/projects/${projectId}/sessions`);
}

async getProjectConfigurations(projectId: string): Promise<ProjectConfigurationsResponse> {
  return this.fetch<ProjectConfigurationsResponse>(`/v2/projects/${projectId}/configurations`);
}

async startDebugInProject(projectId: string, configName?: string): Promise<StartDebugResponse> {
  const url = configName
    ? `/v2/projects/${projectId}/debug/start?config=${encodeURIComponent(configName)}`
    : `/v2/projects/${projectId}/debug/start`;
  return this.fetch<StartDebugResponse>(url, { method: 'POST' });
}
```

## types.ts Updates

Add new types:

```typescript
export interface ProjectInfo {
  id: string;
  name: string;
  path: string;
  isDefault?: boolean;
  sessionCount?: number;
}

export interface ProjectsResponse {
  projects: ProjectInfo[];
}

export interface SessionInfo {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  status: DebugStatus;
  location?: DebugLocation;
}

export interface SessionsResponse {
  sessions: SessionInfo[];
}

export interface SessionDetailResponse {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  status: DebugStatus;
  location?: DebugLocation;
  stackTrace?: StackFrame[];
}

export interface SessionActionResponse {
  success: boolean;
  message: string;
  sessionId: string;
  session?: SessionDetailResponse;
}

export interface StartDebugResponse {
  success: boolean;
  message: string;
  sessionId?: string;
  projectId: string;
}

export type DebugStatus = 'RUNNING' | 'SUSPENDED' | 'TERMINATED' | 'NO_SESSION';
```

## Migration Strategy

### Phase 1: Add Discovery Tools
1. Add `debug_list_projects` and `debug_list_sessions` tools
2. These are non-breaking additions

### Phase 2: Add V2 Session Tools (Keep V1)
1. Add new tools with `_v2` suffix or `sessionId` parameter
2. Keep V1 tools for backward compatibility
3. Example: `debug_step_over` (V1) + `debug_step_over_session` (V2)

### Phase 3: Update Existing Tools
1. Add optional `sessionId` parameter to existing tools
2. If `sessionId` provided → use V2 API
3. If no `sessionId` → use V1 API (backward compatible)

## Recommended Tool Naming

| V1 Tool | V2 Tool (add new) |
|---------|-------------------|
| `debug_get_state` | Keep as-is (uses "current" session) |
| `debug_step_over` | `debug_session_step_over` with `sessionId` param |
| `debug_resume` | `debug_session_resume` with `sessionId` param |
| (none) | `debug_list_projects` (NEW) |
| (none) | `debug_list_sessions` (NEW) |
| (none) | `debug_start_in_project` (NEW) |

## Testing

After upgrade, test with:

```bash
# 1. List projects
curl http://localhost:19999/v2/projects

# 2. List sessions
curl http://localhost:19999/v2/sessions

# 3. Get session details
curl http://localhost:19999/v2/sessions/{sessionId}

# 4. Step over in specific session
curl -X POST http://localhost:19999/v2/sessions/{sessionId}/step-over

# 5. Start debug in specific project
curl -X POST "http://localhost:19999/v2/projects/{projectId}/debug/start?config=MyApp"
```

## Postman Collection

Updated Postman collection is available at:
`postman/IntelliJ_Debug_Bridge.postman_collection.json`

Import it to test all V2 endpoints.

## Summary

The V2 API enables:
1. **Multi-project support** - Debug multiple IntelliJ projects simultaneously
2. **Explicit session targeting** - No more "current session" ambiguity
3. **Better error messages** - Includes available sessions/projects in errors
4. **Proper resource hierarchy** - Projects contain sessions, sessions have state

This is critical for AI-assisted debugging across multiple codebases.
