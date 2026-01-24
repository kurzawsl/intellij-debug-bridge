# IntelliJ Debug Bridge Plugin

## MISSION CRITICAL - READ FIRST

You are building a **REVOLUTIONARY** IntelliJ plugin that will change how developers learn to code.

This plugin bridges IntelliJ's debugger to an MCP server, allowing Claude (AI) to:
1. SEE where the debugger is stopped
2. SEE variable values at breakpoints
3. SEE the call stack
4. CONTROL the debugger (step over, step into, resume)

**This solves the biggest problem of AI-assisted coding: developers don't understand the code AI generates because they don't debug through it.**

## Architecture

```
┌─────────────┐     JDWP      ┌─────────────────────────────┐
│   Your App  │◄────────────►│  IntelliJ Debugger          │
│  (JVM:5005) │              │  + Debug Bridge Plugin      │
└─────────────┘              └──────────────┬──────────────┘
                                            │
                              Plugin intercepts debug events
                              via XDebuggerManagerListener
                                            │
                                            ▼
                             ┌──────────────────────────────┐
                             │  REST API (localhost:19999)  │
                             │  - GET /debug/state          │
                             │  - GET /debug/variables      │
                             │  - POST /debug/step-over     │
                             │  - POST /debug/resume        │
                             └──────────────┬───────────────┘
                                            │
                                            ▼
                             ┌──────────────────────────────┐
                             │  MCP Server (separate repo)  │
                             │  intellij-debugger           │
                             └──────────────────────────────┘
```

## Tech Stack

- **Language**: Kotlin (preferred for IntelliJ plugins) or Java
- **IntelliJ Platform SDK**: Latest stable
- **HTTP Server**: Ktor embedded or Netty (lightweight)
- **JSON**: kotlinx.serialization or Gson

## Key IntelliJ APIs to Use

### Debug Events
```kotlin
// Listen for debug session events
com.intellij.xdebugger.XDebuggerManager
com.intellij.xdebugger.XDebuggerManagerListener
com.intellij.xdebugger.XDebugSession
com.intellij.xdebugger.frame.XSuspendContext
com.intellij.xdebugger.frame.XStackFrame
com.intellij.xdebugger.frame.XValue
```

### Breakpoint Management
```kotlin
com.intellij.xdebugger.breakpoints.XBreakpointManager
com.intellij.xdebugger.breakpoints.XLineBreakpoint
```

### Variable Inspection
```kotlin
com.intellij.xdebugger.frame.XValueContainer
com.intellij.xdebugger.frame.XValueChildrenList
```

## REST API Endpoints to Implement

### GET /debug/state
Returns current debug state:
```json
{
  "status": "suspended",
  "sessionName": "ClaudeAssistantApplication",
  "location": {
    "file": "TelegramBot.java",
    "filePath": "/src/main/java/.../TelegramBot.java",
    "line": 86,
    "method": "handleText",
    "class": "com.morpheus.assistant.telegram.TelegramBot"
  },
  "stackTrace": [
    {"method": "handleText", "file": "TelegramBot.java", "line": 86},
    {"method": "onUpdateReceived", "file": "TelegramBot.java", "line": 71}
  ]
}
```

### GET /debug/variables
Returns local variables at current position:
```json
{
  "locals": [
    {"name": "chatId", "type": "Long", "value": "123456789"},
    {"name": "text", "type": "String", "value": "Hello world"},
    {"name": "response", "type": "String", "value": "null"}
  ],
  "this": {
    "type": "TelegramBot",
    "fields": [
      {"name": "token", "type": "String", "value": "***"},
      {"name": "chatService", "type": "LangChain4jChatService", "value": "@12345"}
    ]
  }
}
```

### POST /debug/evaluate
Evaluate expression in current context:
```json
// Request
{"expression": "chatId.toString()"}

// Response
{"result": "123456789", "type": "String"}
```

### POST /debug/step-over
Execute step over (F8), returns new state.

### POST /debug/step-into
Execute step into (F7), returns new state.

### POST /debug/step-out
Execute step out (Shift+F8), returns new state.

### POST /debug/resume
Resume execution (F9), returns new state.

### GET /debug/breakpoints
List all breakpoints:
```json
{
  "breakpoints": [
    {"file": "TelegramBot.java", "line": 86, "enabled": true},
    {"file": "LangChain4jChatService.java", "line": 74, "enabled": true}
  ]
}
```

### POST /debug/breakpoints
Add breakpoint:
```json
{"file": "TelegramBot.java", "line": 86}
```

### DELETE /debug/breakpoints
Remove breakpoint:
```json
{"file": "TelegramBot.java", "line": 86}
```

## Plugin Structure

```
intellij-debug-bridge/
├── build.gradle.kts
├── settings.gradle.kts
├── src/main/
│   ├── kotlin/com/morpheus/debugbridge/
│   │   ├── DebugBridgePlugin.kt          # Plugin entry point
│   │   ├── DebugSessionListener.kt       # XDebuggerManagerListener impl
│   │   ├── DebugStateService.kt          # Tracks current debug state
│   │   ├── RestServer.kt                 # Ktor/Netty HTTP server
│   │   ├── api/
│   │   │   ├── DebugStateController.kt
│   │   │   ├── VariablesController.kt
│   │   │   └── ControlController.kt
│   │   └── model/
│   │       ├── DebugState.kt
│   │       ├── StackFrame.kt
│   │       └── Variable.kt
│   └── resources/
│       └── META-INF/
│           └── plugin.xml                # Plugin descriptor
└── README.md
```

## plugin.xml Template

```xml
<idea-plugin>
    <id>com.morpheus.debug-bridge</id>
    <name>Debug Bridge for AI</name>
    <vendor>Morpheus</vendor>
    <description>Bridges IntelliJ debugger to AI assistants via REST API</description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.xdebugger</depends>

    <extensions defaultExtensionNs="com.intellij">
        <postStartupActivity implementation="com.morpheus.debugbridge.DebugBridgeStartup"/>
    </extensions>

    <projectListeners>
        <listener class="com.morpheus.debugbridge.DebugSessionListener"
                  topic="com.intellij.xdebugger.XDebuggerManagerListener"/>
    </projectListeners>
</idea-plugin>
```

## Development Setup

1. Use IntelliJ IDEA (Ultimate or Community)
2. Install "Plugin DevKit" plugin
3. Create new "IntelliJ Platform Plugin" project
4. Target IntelliJ 2024.1+ for latest APIs

## Testing

1. Run plugin in sandbox IDE (Gradle runIde task)
2. Open a Java project in sandbox
3. Set breakpoint, start debug
4. Hit breakpoint
5. Call REST API: `curl http://localhost:19999/debug/state`
6. Verify response contains location and variables

## Security Considerations

- REST server binds to localhost only (127.0.0.1)
- No authentication needed for localhost
- Don't expose sensitive variable values (mask passwords, tokens)
- Add configurable port in settings

## Success Criteria

1. Plugin installs without errors
2. REST server starts when IDE starts
3. /debug/state returns correct location when at breakpoint
4. /debug/variables returns local variables
5. /debug/step-over advances debugger
6. /debug/resume continues execution

## Multi-Project Architecture (v2)

**See: [docs/MULTI_PROJECT_ARCHITECTURE.md](docs/MULTI_PROJECT_ARCHITECTURE.md)**

The plugin supports debugging multiple microservices simultaneously with:

### Key Concepts
- **Project ID**: SHA-256 hash of project base path (stable, deterministic)
- **Session ID**: UUID generated when debug session starts
- **No focus/context**: Every API call explicitly targets a project or session
- **Backward compatible**: Old endpoints still work for single-project use

### API Structure
```
GET  /projects                              # List all open projects
GET  /sessions                              # List all debug sessions
GET  /sessions/{sessionId}                  # Get session state
POST /sessions/{sessionId}/step-over        # Step in specific session
POST /projects/{projectId}/debug/start      # Start debug in project
POST /projects/{projectId}/breakpoints      # Add breakpoint to project
```

### Architecture
```
AppRegistryService (Application-Level)
├── projects: Map<projectId, ProjectStateService>
├── sessions: Map<sessionId, SessionReference>
└── lastTouchedSession: String?

ProjectStateService (Project-Level, one per project)
├── projectId: String
├── sessions: Map<sessionId, XDebugSession>
└── configurations, breakpoints, etc.
```

## The Vision

This plugin + MCP will enable:
- AI-guided debugging sessions
- Interactive learning ("let me show you the flow")
- Pair programming with AI that SEES what you see
- Teaching debugging skills to millions of developers
- **Multi-service debugging** for microservices architectures

**BUILD SOMETHING LEGENDARY!**
