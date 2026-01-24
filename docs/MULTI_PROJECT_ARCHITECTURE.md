# Multi-Project/Multi-Session Architecture

## Executive Summary

This document proposes a new architecture for the IntelliJ Debug Bridge plugin to support debugging multiple microservices simultaneously. The architecture enables Claude Code (via MCP) to control debug sessions across multiple projects independently.

## Problem Statement

### Current Limitations

1. **Single Active Session**: `DebugStateService` has only ONE `activeSession` - can't debug multiple services
2. **No Project Identification**: REST API calls don't specify which project to target
3. **First-Project-Wins**: `DebugController` uses `projects.firstOrNull()` - randomly picks first project
4. **Mixed Data**: `getModules()`/`getConfigurations()` return data from ALL projects without distinction
5. **No Session Targeting**: Can't say "step over in accounting-service debug session"

### Use Case: Microservices Debugging

Developer has IntelliJ open with 3 microservices:
- `payment-service` (port 8081, debug port 5006)
- `accounting-service` (port 8082, debug port 5007)
- `api-gateway` (port 8080, debug port 5005)

Claude Code needs to:
1. List all 3 projects
2. Start debugging api-gateway
3. Set breakpoint in payment-service
4. When api-gateway calls payment-service, both hit breakpoints
5. Step through api-gateway while payment-service waits
6. Switch to payment-service, inspect variables
7. Resume payment-service
8. Go back to api-gateway and continue

---

## Proposed Architecture: Centralized Registry

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           IntelliJ IDEA                                     │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                    APPLICATION LEVEL (Singleton)                      │  │
│  │                                                                       │  │
│  │  ┌─────────────────┐           ┌───────────────────────────────────┐ │  │
│  │  │  RestServer     │◄──REST───►│  AppRegistryService               │ │  │
│  │  │  (Ktor:19999)   │           │  - projects: Map<ID, ProjectState>│ │  │
│  │  │                 │           │  - sessions: Map<ID, SessionInfo> │ │  │
│  │  │  /projects      │           │  - generateProjectId()            │ │  │
│  │  │  /sessions      │           │  - generateSessionId()            │ │  │
│  │  │  /sessions/{id} │           │  - lookupSession()                │ │  │
│  │  └─────────────────┘           │  - lookupProject()                │ │  │
│  │                                └───────────────────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                       ▲                                     │
│                                       │ register/unregister                 │
│                                       │                                     │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                        PROJECT LEVEL (per project)                       ││
│  │                                                                          ││
│  │  ┌─────────────────────────────┐  ┌─────────────────────────────────┐   ││
│  │  │ ProjectStateService         │  │ ProjectStateService             │   ││
│  │  │ [payment-service]           │  │ [accounting-service]            │   ││
│  │  │                             │  │                                 │   ││
│  │  │ - projectId: "sha256..."    │  │ - projectId: "sha256..."        │   ││
│  │  │ - sessions: Map<UUID, Sess> │  │ - sessions: Map<UUID, Session>  │   ││
│  │  │ - configurations: [...]     │  │ - configurations: [...]         │   ││
│  │  │ - breakpoints: [...]        │  │ - breakpoints: [...]            │   ││
│  │  └─────────────────────────────┘  └─────────────────────────────────┘   ││
│  └─────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────┘
                                       ▲
                                       │ REST over HTTP
                                       │
┌─────────────────────────────────────────────────────────────────────────────┐
│                         MCP Server (intellij-debugger)                      │
│                                                                             │
│  - debug_list_projects()      → GET /projects                               │
│  - debug_list_sessions()      → GET /sessions                               │
│  - debug_get_state(sessionId) → GET /sessions/{id}                          │
│  - debug_step_over(sessionId) → POST /sessions/{id}/step-over               │
│  - debug_set_breakpoint(...)  → POST /projects/{id}/breakpoints             │
└─────────────────────────────────────────────────────────────────────────────┘
                                       ▲
                                       │ MCP Protocol
                                       │
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Claude Code                                     │
│                                                                             │
│  "Step over in payment-service session"                                     │
│  "Show me variables in api-gateway"                                         │
│  "Set breakpoint in AccountingController.java line 42"                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Identification Strategy

### Project Identification

**Method**: SHA-256 hash of project's `basePath`

```kotlin
val projectId = MessageDigest.getInstance("SHA-256")
    .digest(project.basePath!!.toByteArray())
    .take(8) // First 8 bytes = 16 hex chars
    .joinToString("") { "%02x".format(it) }

// Example: "a1b2c3d4e5f6g7h8"
```

**Properties**:
- ✅ Stable across IDE restarts
- ✅ Deterministic (same path → same ID)
- ✅ Unique (extremely low collision probability)
- ✅ URL-safe (hexadecimal)

**Display**: Always return both ID and human-readable name:
```json
{
  "id": "a1b2c3d4e5f6g7h8",
  "name": "payment-service",
  "path": "/Users/dev/workspace/payment-service"
}
```

### Session Identification

**Method**: UUID v4 generated when session starts

```kotlin
val sessionId = UUID.randomUUID().toString()
// Example: "550e8400-e29b-41d4-a716-446655440000"
```

**Properties**:
- ✅ Universally unique
- ✅ No collisions possible
- ✅ URL-safe
- ✅ Standard format

**Display**: Include session name, project reference, and status:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "PaymentServiceApplication",
  "projectId": "a1b2c3d4e5f6g7h8",
  "projectName": "payment-service",
  "status": "SUSPENDED",
  "location": { ... }
}
```

---

## API Design

### Design Principles

1. **NO FOCUS/CONTEXT**: Every call is stateless and explicit
2. **HIERARCHICAL**: Project → Session hierarchy in endpoints
3. **DISCOVERABLE**: AI can always find what to target
4. **BACKWARD COMPATIBLE**: Old endpoints still work for single-project use

### New Endpoints

#### Discovery Endpoints

```
GET /projects
```
List all open projects with their identifiers.

Response:
```json
{
  "projects": [
    {
      "id": "a1b2c3d4e5f6g7h8",
      "name": "payment-service",
      "path": "/Users/dev/workspace/payment-service",
      "isDefault": false
    },
    {
      "id": "b2c3d4e5f6g7h8i9",
      "name": "api-gateway",
      "path": "/Users/dev/workspace/api-gateway",
      "isDefault": false
    }
  ],
  "count": 2
}
```

---

```
GET /sessions
```
List all active debug sessions across all projects.

Response:
```json
{
  "sessions": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "PaymentServiceApplication",
      "projectId": "a1b2c3d4e5f6g7h8",
      "projectName": "payment-service",
      "status": "SUSPENDED",
      "location": {
        "file": "PaymentController.java",
        "line": 42,
        "method": "processPayment"
      }
    },
    {
      "id": "661f9511-f30c-52e5-b827-557766551111",
      "name": "ApiGatewayApplication",
      "projectId": "b2c3d4e5f6g7h8i9",
      "projectName": "api-gateway",
      "status": "RUNNING",
      "location": null
    }
  ],
  "count": 2
}
```

---

#### Session-Scoped Endpoints

```
GET /sessions/{sessionId}
```
Get detailed state of a specific session.

Response:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "PaymentServiceApplication",
  "projectId": "a1b2c3d4e5f6g7h8",
  "projectName": "payment-service",
  "status": "SUSPENDED",
  "location": {
    "file": "PaymentController.java",
    "filePath": "/src/main/java/.../PaymentController.java",
    "line": 42,
    "method": "processPayment",
    "className": "com.example.payment.PaymentController"
  },
  "stackTrace": [
    { "index": 0, "method": "processPayment", "file": "PaymentController.java", "line": 42 },
    { "index": 1, "method": "handleRequest", "file": "BaseController.java", "line": 15 }
  ]
}
```

---

```
GET /sessions/{sessionId}/variables
```
Get variables at current position.

---

```
POST /sessions/{sessionId}/step-over
POST /sessions/{sessionId}/step-into
POST /sessions/{sessionId}/step-out
POST /sessions/{sessionId}/resume
POST /sessions/{sessionId}/pause
```
Debug control actions.

Response (all return updated state):
```json
{
  "success": true,
  "message": "Step over executed",
  "session": { ... updated session state ... }
}
```

---

```
DELETE /sessions/{sessionId}
```
Stop/disconnect the debug session.

---

```
POST /sessions/{sessionId}/evaluate
```
Evaluate expression in session context.

Request:
```json
{ "expression": "user.getName()" }
```

---

#### Project-Scoped Endpoints

```
GET /projects/{projectId}
```
Get project details.

---

```
GET /projects/{projectId}/configurations
```
List run/debug configurations for this project.

Response:
```json
{
  "projectId": "a1b2c3d4e5f6g7h8",
  "configurations": [
    { "name": "PaymentServiceApplication", "type": "SPRING_BOOT" },
    { "name": "PaymentServiceTest", "type": "JUNIT_CLASS" }
  ]
}
```

---

```
POST /projects/{projectId}/configurations
```
Create a run configuration in this project.

Request:
```json
{
  "name": "PaymentServiceApplication",
  "type": "SPRING_BOOT",
  "className": "com.example.payment.PaymentServiceApplication",
  "activeProfiles": "dev,local"
}
```

---

```
POST /projects/{projectId}/debug/start
```
Start debugging with specified configuration.

Query params: `?config=PaymentServiceApplication`

Response:
```json
{
  "success": true,
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "message": "Debug session started"
}
```

---

```
GET /projects/{projectId}/breakpoints
```
List breakpoints in this project.

---

```
POST /projects/{projectId}/breakpoints
```
Add a breakpoint in this project.

Request:
```json
{
  "file": "PaymentController.java",
  "line": 42,
  "condition": "amount > 1000"
}
```

---

```
DELETE /projects/{projectId}/breakpoints
```
Remove a breakpoint.

---

```
GET /projects/{projectId}/modules
```
List modules in this project.

---

```
GET /projects/{projectId}/sessions
```
List debug sessions for this specific project only.

---

### Backward Compatibility

Old endpoints (no project/session specifier) will use these fallback rules:

| Old Endpoint | Behavior |
|--------------|----------|
| `GET /debug/state` | Returns state of "most recently touched" session |
| `POST /debug/step-over` | Acts on "most recently touched" session |
| `GET /debug/configurations` | Returns configs from ALL projects (current behavior) |
| `GET /debug/breakpoints` | Returns breakpoints from ALL projects |

"Most recently touched" = Last session that:
- Was created
- Had a step/resume action
- Hit a breakpoint

This ensures single-project users experience no breaking changes.

---

## Implementation Plan

### Phase 1: Core Services Refactor

#### 1.1 Create `ProjectStateService` (Project-Level)

```kotlin
@Service(Service.Level.PROJECT)
class ProjectStateService(private val project: Project) {

    private val projectId: String = generateProjectId(project)
    private val sessions = ConcurrentHashMap<String, SessionInfo>()
    private val log = Logger.getInstance(ProjectStateService::class.java)

    data class SessionInfo(
        val id: String,
        val session: XDebugSession,
        val createdAt: Instant
    )

    init {
        // Register with app-level registry
        AppRegistryService.getInstance().registerProject(this)

        // Listen for debug sessions
        XDebuggerManager.getInstance(project).addDebuggerManagerListener(
            DebugManagerListener(), project
        )
    }

    fun getProjectId(): String = projectId
    fun getProject(): Project = project
    fun getSessions(): List<SessionInfo> = sessions.values.toList()
    fun getSession(sessionId: String): SessionInfo? = sessions[sessionId]

    // Called when project closes
    fun dispose() {
        AppRegistryService.getInstance().unregisterProject(projectId)
        sessions.clear()
    }

    private inner class DebugManagerListener : XDebuggerManagerListener {
        override fun processStarted(debugProcess: XDebugProcess) {
            val session = debugProcess.session
            val sessionId = UUID.randomUUID().toString()
            val info = SessionInfo(sessionId, session, Instant.now())
            sessions[sessionId] = info
            AppRegistryService.getInstance().registerSession(sessionId, projectId, info)
            log.info("Session registered: $sessionId (${session.sessionName})")
        }

        override fun processStopped(debugProcess: XDebugProcess) {
            val session = debugProcess.session
            val entry = sessions.entries.find { it.value.session == session }
            entry?.let {
                sessions.remove(it.key)
                AppRegistryService.getInstance().unregisterSession(it.key)
                log.info("Session unregistered: ${it.key}")
            }
        }
    }

    companion object {
        fun getInstance(project: Project): ProjectStateService {
            return project.getService(ProjectStateService::class.java)
        }

        fun generateProjectId(project: Project): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(project.basePath!!.toByteArray())
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
```

#### 1.2 Create `AppRegistryService` (Application-Level)

```kotlin
@Service(Service.Level.APP)
class AppRegistryService {

    private val projects = ConcurrentHashMap<String, ProjectStateService>()
    private val sessions = ConcurrentHashMap<String, SessionReference>()
    private val lastTouchedSession = AtomicReference<String?>(null)
    private val log = Logger.getInstance(AppRegistryService::class.java)

    data class SessionReference(
        val sessionId: String,
        val projectId: String,
        val sessionInfo: ProjectStateService.SessionInfo
    )

    // Project registration
    fun registerProject(projectState: ProjectStateService) {
        projects[projectState.getProjectId()] = projectState
        log.info("Project registered: ${projectState.getProjectId()} (${projectState.getProject().name})")
    }

    fun unregisterProject(projectId: String) {
        projects.remove(projectId)
        // Clean up any sessions from this project
        sessions.entries.removeIf { it.value.projectId == projectId }
        log.info("Project unregistered: $projectId")
    }

    // Session registration
    fun registerSession(sessionId: String, projectId: String, info: ProjectStateService.SessionInfo) {
        sessions[sessionId] = SessionReference(sessionId, projectId, info)
        lastTouchedSession.set(sessionId)
    }

    fun unregisterSession(sessionId: String) {
        sessions.remove(sessionId)
        if (lastTouchedSession.get() == sessionId) {
            lastTouchedSession.set(sessions.keys.firstOrNull())
        }
    }

    // Lookup methods
    fun getAllProjects(): List<ProjectStateService> = projects.values.toList()
    fun getProject(projectId: String): ProjectStateService? = projects[projectId]

    fun getAllSessions(): List<SessionReference> = sessions.values.toList()
    fun getSession(sessionId: String): SessionReference? = sessions[sessionId]

    fun getLastTouchedSession(): SessionReference? {
        return lastTouchedSession.get()?.let { sessions[it] }
    }

    fun touchSession(sessionId: String) {
        if (sessions.containsKey(sessionId)) {
            lastTouchedSession.set(sessionId)
        }
    }

    companion object {
        fun getInstance(): AppRegistryService {
            return ApplicationManager.getApplication().getService(AppRegistryService::class.java)
        }
    }
}
```

### Phase 2: API V2 Implementation

Update `RestServer.kt` to add new routes while keeping old ones for compatibility.

### Phase 3: MCP Server Update

Update `intellij-debugger` MCP server to use new endpoints:

```typescript
// Old tool (still works)
debug_get_state()

// New tools
debug_list_projects()
debug_list_sessions()
debug_get_session(sessionId: string)
debug_step_over(sessionId: string)
debug_set_breakpoint(projectId: string, file: string, line: number)
debug_start_debug(projectId: string, config: string)
```

---

## Error Handling

### Error Response Format

```json
{
  "success": false,
  "error": "SESSION_NOT_FOUND",
  "message": "Session 550e8400-e29b-41d4-a716-446655440000 not found",
  "availableSessions": ["661f9511-f30c-52e5-b827-557766551111"]
}
```

### Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `PROJECT_NOT_FOUND` | 404 | Project ID doesn't exist |
| `SESSION_NOT_FOUND` | 404 | Session ID doesn't exist |
| `SESSION_NOT_SUSPENDED` | 409 | Can't step - session is running |
| `SESSION_NOT_RUNNING` | 409 | Can't pause - session is already paused |
| `NO_ACTIVE_SESSIONS` | 404 | No debug sessions active |
| `FILE_NOT_FOUND` | 404 | File not found in project |
| `CONFIGURATION_NOT_FOUND` | 404 | Run config doesn't exist |

---

## AI Workflow Example

```
User: "Debug the payment flow from api-gateway to payment-service"

Claude Code:
1. GET /projects
   → Sees: api-gateway (id: abc), payment-service (id: def)

2. POST /projects/def/breakpoints
   Body: { "file": "PaymentController.java", "line": 42 }
   → Breakpoint set in payment-service

3. POST /projects/abc/debug/start?config=ApiGatewayApplication
   → Session started, returns sessionId: "session-1"

4. POST /projects/def/debug/start?config=PaymentServiceApplication
   → Session started, returns sessionId: "session-2"

5. GET /sessions
   → Both sessions running

6. [User triggers API call in browser]

7. GET /sessions
   → session-1 (api-gateway): SUSPENDED at line 55
   → session-2 (payment-service): RUNNING

8. POST /sessions/session-1/step-over
   → Steps api-gateway

9. GET /sessions
   → session-1 (api-gateway): RUNNING (waiting for payment response)
   → session-2 (payment-service): SUSPENDED at line 42

10. GET /sessions/session-2/variables
    → Shows payment request data

11. POST /sessions/session-2/resume
    → Payment service continues

12. GET /sessions
    → session-1 (api-gateway): SUSPENDED at line 60 (got response)
    → session-2 (payment-service): RUNNING

Claude Code: "The payment flow works. The request from api-gateway
             successfully reached payment-service..."
```

---

## Migration Path

### For Plugin Users

1. **No breaking changes** - Old endpoints continue to work
2. **Gradual adoption** - New endpoints available when needed
3. **Clear deprecation** - Old endpoints marked deprecated after 6 months

### For MCP Server

1. **Add new tools** - Keep old tools working
2. **Update tool descriptions** - Guide AI to use new tools
3. **Deprecate old tools** - After confirming stability

---

## Future Enhancements

### Considered but Deferred

1. **WebSocket subscriptions** - Real-time state updates
2. **Conditional breakpoint evaluation** - Evaluate on hit
3. **Watch expressions** - Track values across steps
4. **Hot code reload integration** - Detect code changes
5. **Remote debugging** - Debug processes on remote machines
6. **Collaborative debugging** - Multiple AIs debugging together

---

## Conclusion

This architecture provides:

1. **Full multi-project support** - Debug any number of microservices
2. **Explicit session targeting** - No ambiguity about which session to control
3. **Stateless API** - Perfect for MCP/AI consumption
4. **Backward compatibility** - Existing single-project workflows unchanged
5. **Intuitive identifiers** - Both stable IDs and human-readable names
6. **Robust cleanup** - Sessions/projects properly cleaned up on close

The Centralized Registry approach offers the best balance of simplicity, power, and maintainability for AI-assisted debugging scenarios.
