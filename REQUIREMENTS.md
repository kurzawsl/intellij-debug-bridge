# IntelliJ Debug Bridge - Requirements Specification

## Overview

An IntelliJ IDEA plugin that exposes debugger state and control via REST API, enabling AI assistants to participate in debugging sessions.

## Functional Requirements

### FR1: Debug State Monitoring

| ID | Requirement | Priority |
|----|-------------|----------|
| FR1.1 | Plugin SHALL detect when a debug session starts | P0 |
| FR1.2 | Plugin SHALL detect when execution is suspended (breakpoint hit) | P0 |
| FR1.3 | Plugin SHALL capture current file, line, method, class when suspended | P0 |
| FR1.4 | Plugin SHALL capture full stack trace when suspended | P0 |
| FR1.5 | Plugin SHALL detect when debug session ends | P0 |
| FR1.6 | Plugin SHALL track multiple simultaneous debug sessions | P1 |

### FR2: Variable Inspection

| ID | Requirement | Priority |
|----|-------------|----------|
| FR2.1 | Plugin SHALL read local variables at current stack frame | P0 |
| FR2.2 | Plugin SHALL read 'this' object fields | P0 |
| FR2.3 | Plugin SHALL handle primitive types (int, long, boolean, etc.) | P0 |
| FR2.4 | Plugin SHALL handle String values | P0 |
| FR2.5 | Plugin SHALL handle null values | P0 |
| FR2.6 | Plugin SHALL handle object references (show type + id) | P1 |
| FR2.7 | Plugin SHALL handle arrays (show length + first N elements) | P1 |
| FR2.8 | Plugin SHALL handle collections (show size + first N elements) | P2 |
| FR2.9 | Plugin SHALL mask sensitive values (password, token, secret, key) | P0 |
| FR2.10 | Plugin SHALL support expanding nested objects (depth limit) | P2 |

### FR3: Expression Evaluation

| ID | Requirement | Priority |
|----|-------------|----------|
| FR3.1 | Plugin SHALL evaluate simple expressions in current context | P1 |
| FR3.2 | Plugin SHALL return evaluated value and type | P1 |
| FR3.3 | Plugin SHALL handle evaluation errors gracefully | P1 |
| FR3.4 | Plugin SHALL support method calls in expressions | P2 |

### FR4: Debugger Control

| ID | Requirement | Priority |
|----|-------------|----------|
| FR4.1 | Plugin SHALL support step over (F8) | P0 |
| FR4.2 | Plugin SHALL support step into (F7) | P0 |
| FR4.3 | Plugin SHALL support step out (Shift+F8) | P1 |
| FR4.4 | Plugin SHALL support resume (F9) | P0 |
| FR4.5 | Plugin SHALL support run to cursor (specific line) | P2 |
| FR4.6 | Plugin SHALL return new state after each control operation | P0 |

### FR5: Breakpoint Management

| ID | Requirement | Priority |
|----|-------------|----------|
| FR5.1 | Plugin SHALL list all line breakpoints | P0 |
| FR5.2 | Plugin SHALL add line breakpoints | P0 |
| FR5.3 | Plugin SHALL remove line breakpoints | P0 |
| FR5.4 | Plugin SHALL enable/disable breakpoints | P1 |
| FR5.5 | Plugin SHALL support conditional breakpoints | P2 |
| FR5.6 | Plugin SHALL support log breakpoints (tracepoints) | P3 |

### FR6: REST API Server

| ID | Requirement | Priority |
|----|-------------|----------|
| FR6.1 | Plugin SHALL start HTTP server on IDE startup | P0 |
| FR6.2 | Plugin SHALL bind to localhost only (127.0.0.1) | P0 |
| FR6.3 | Plugin SHALL use configurable port (default: 19999) | P0 |
| FR6.4 | Plugin SHALL return JSON responses | P0 |
| FR6.5 | Plugin SHALL handle CORS for local development | P1 |
| FR6.6 | Plugin SHALL gracefully handle server errors | P0 |

## Non-Functional Requirements

### NFR1: Performance

| ID | Requirement |
|----|-------------|
| NFR1.1 | API response time SHALL be < 100ms for state queries |
| NFR1.2 | Variable inspection SHALL handle objects with 100+ fields |
| NFR1.3 | Plugin SHALL not noticeably slow down IDE |

### NFR2: Reliability

| ID | Requirement |
|----|-------------|
| NFR2.1 | Plugin SHALL recover from debug session crashes |
| NFR2.2 | Plugin SHALL handle concurrent API requests |
| NFR2.3 | Server SHALL auto-restart if it crashes |

### NFR3: Compatibility

| ID | Requirement |
|----|-------------|
| NFR3.1 | Plugin SHALL support IntelliJ IDEA 2024.1+ |
| NFR3.2 | Plugin SHALL support Java debugging |
| NFR3.3 | Plugin SHOULD support Kotlin debugging |
| NFR3.4 | Plugin MAY support other JVM languages |

## API Specification

### Base URL
```
http://localhost:19999
```

### Endpoints

#### GET /debug/state
Get current debug state.

**Response 200:**
```json
{
  "status": "suspended" | "running" | "not_debugging",
  "sessionId": "string",
  "sessionName": "string",
  "location": {
    "file": "string",
    "filePath": "string",
    "line": number,
    "column": number,
    "method": "string",
    "class": "string",
    "package": "string"
  },
  "stackTrace": [
    {
      "index": number,
      "method": "string",
      "file": "string",
      "line": number,
      "class": "string"
    }
  ],
  "threadName": "string",
  "threadId": number
}
```

#### GET /debug/variables?frame=0&depth=2
Get variables at specified stack frame.

**Parameters:**
- `frame` (optional): Stack frame index, default 0 (current)
- `depth` (optional): Object expansion depth, default 1

**Response 200:**
```json
{
  "frameIndex": number,
  "locals": [
    {
      "name": "string",
      "type": "string",
      "value": "string" | number | boolean | null,
      "isObject": boolean,
      "objectId": "string",
      "children": [] // if depth > 1
    }
  ],
  "this": {
    "type": "string",
    "objectId": "string",
    "fields": [
      {
        "name": "string",
        "type": "string",
        "value": "string" | number | boolean | null,
        "modifier": "private" | "protected" | "public"
      }
    ]
  }
}
```

#### POST /debug/evaluate
Evaluate expression in current context.

**Request:**
```json
{
  "expression": "string",
  "frame": number // optional, default 0
}
```

**Response 200:**
```json
{
  "success": true,
  "result": "string",
  "type": "string"
}
```

**Response 400:**
```json
{
  "success": false,
  "error": "string"
}
```

#### POST /debug/step-over
Execute step over.

**Response 200:** Same as GET /debug/state

#### POST /debug/step-into
Execute step into.

**Response 200:** Same as GET /debug/state

#### POST /debug/step-out
Execute step out.

**Response 200:** Same as GET /debug/state

#### POST /debug/resume
Resume execution.

**Response 200:** Same as GET /debug/state (status will be "running")

#### GET /debug/breakpoints
List all breakpoints.

**Response 200:**
```json
{
  "breakpoints": [
    {
      "id": "string",
      "file": "string",
      "filePath": "string",
      "line": number,
      "enabled": boolean,
      "condition": "string" | null,
      "logExpression": "string" | null
    }
  ]
}
```

#### POST /debug/breakpoints
Add a breakpoint.

**Request:**
```json
{
  "file": "string", // filename or full path
  "line": number,
  "condition": "string", // optional
  "enabled": boolean // optional, default true
}
```

**Response 201:**
```json
{
  "id": "string",
  "file": "string",
  "line": number,
  "enabled": boolean
}
```

#### DELETE /debug/breakpoints
Remove a breakpoint.

**Request:**
```json
{
  "file": "string",
  "line": number
}
```

**Response 200:**
```json
{
  "removed": true
}
```

### Error Responses

**404 - No Debug Session:**
```json
{
  "error": "no_debug_session",
  "message": "No active debug session"
}
```

**409 - Not Suspended:**
```json
{
  "error": "not_suspended",
  "message": "Debugger is running, not suspended at breakpoint"
}
```

**500 - Internal Error:**
```json
{
  "error": "internal_error",
  "message": "string"
}
```

## Configuration

Plugin settings (in IDE Settings > Tools > Debug Bridge):

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| Enable REST Server | boolean | true | Start server on IDE launch |
| Port | number | 19999 | REST API port |
| Mask Sensitive Values | boolean | true | Hide passwords, tokens |
| Sensitive Patterns | string[] | ["password", "token", "secret", "key", "api_key"] | Patterns to mask |
| Max Object Depth | number | 3 | Maximum depth for object expansion |
| Max Array Elements | number | 10 | Max elements to return for arrays |

## Implementation Phases

### Phase 1 - MVP (Week 1)
- [ ] Project setup with Gradle
- [ ] Basic plugin structure
- [ ] HTTP server startup
- [ ] GET /debug/state endpoint
- [ ] Debug session listener
- [ ] Location capture on breakpoint hit

### Phase 2 - Variables (Week 2)
- [ ] GET /debug/variables endpoint
- [ ] Local variable reading
- [ ] 'this' object inspection
- [ ] Primitive and String handling
- [ ] Sensitive value masking

### Phase 3 - Control (Week 2-3)
- [ ] POST /debug/step-over
- [ ] POST /debug/step-into
- [ ] POST /debug/resume
- [ ] POST /debug/step-out

### Phase 4 - Breakpoints (Week 3)
- [ ] GET /debug/breakpoints
- [ ] POST /debug/breakpoints
- [ ] DELETE /debug/breakpoints
- [ ] Breakpoint enable/disable

### Phase 5 - Advanced (Week 4+)
- [ ] POST /debug/evaluate
- [ ] Conditional breakpoints
- [ ] Object expansion depth
- [ ] Multiple debug sessions
- [ ] Settings UI

## Testing Strategy

### Unit Tests
- API response formatting
- Variable serialization
- Sensitive value masking

### Integration Tests
- Plugin sandbox testing
- Debug session simulation
- API endpoint verification

### Manual Tests
- Real debugging session with breakpoints
- Variable inspection accuracy
- Step control correctness

## Success Metrics

1. **Installation**: Plugin installs in IntelliJ without errors
2. **Startup**: REST server starts within 2 seconds of IDE launch
3. **State Accuracy**: /debug/state returns correct location 100% of time
4. **Variable Accuracy**: /debug/variables returns correct values for primitives and strings
5. **Control Reliability**: Step/resume commands work reliably
6. **Performance**: API responses < 100ms

## Future Enhancements

1. WebSocket for real-time debug events (push instead of poll)
2. Support for VSCode via Debug Adapter Protocol
3. Watch expressions management
4. Memory/heap inspection
5. Thread control (pause/resume specific threads)
6. Hot code replacement trigger
7. Debug configuration management
