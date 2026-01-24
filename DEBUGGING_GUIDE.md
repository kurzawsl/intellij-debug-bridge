# Debugging IntelliJ Debug Bridge Plugin

## Project Type
This is a **Kotlin/Gradle IntelliJ Platform Plugin**, NOT a Node.js project.

- Build system: Gradle (Kotlin DSL)
- Language: Kotlin 2.1.0
- Target: IntelliJ IDEA 2024.3+
- REST framework: Ktor

---

## Method 1: Run in IDE Sandbox

The easiest way to debug the plugin:

```bash
# Run IntelliJ sandbox with the plugin
./gradlew runIde
```

This:
1. Downloads IntelliJ Community if needed
2. Creates a sandbox environment
3. Installs your plugin
4. Launches IntelliJ with the plugin loaded

### View Logs
```bash
# During runIde, logs appear in:
~/Library/Logs/IdeaIC*/idea.log

# Filter for plugin logs:
tail -f ~/Library/Logs/IdeaIC*/idea.log | grep "Debug Bridge"
```

---

## Method 2: Remote Debugging (Attach to Sandbox)

### Step 1: Configure Gradle to enable debug
Edit `gradle.properties`:
```properties
# Add debug JVM args
org.gradle.jvmargs=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5006
```

Or run with:
```bash
./gradlew runIde --debug-jvm
```

### Step 2: Create IntelliJ Run Configuration
1. Open your MAIN IntelliJ (where you develop the plugin)
2. Run → Edit Configurations → + → Remote JVM Debug
3. Settings:
   - Name: "Debug Bridge Sandbox"
   - Host: localhost
   - Port: 5006
   - Use module classpath: intellij-debug-bridge

### Step 3: Debug
1. Run `./gradlew runIde --debug-jvm` (waits for debugger)
2. In main IntelliJ, click Debug on "Debug Bridge Sandbox"
3. Sandbox resumes and you can hit breakpoints

---

## Method 3: Debug the REST API Only

If you just want to debug REST endpoint handling:

### Add Logging
```kotlin
// In RestServer.kt
private val log = Logger.getInstance(RestServer::class.java)

get("/debug/state") {
    log.info("GET /debug/state called")
    val state = DebugStateService.getInstance().getState()
    log.info("Returning state: $state")
    call.respond(state)
}
```

### Test with curl
```bash
# Start sandbox
./gradlew runIde

# In another terminal, test API
curl -v http://localhost:19999/debug/state | jq
curl -X POST http://localhost:19999/debug/step-over | jq
curl http://localhost:19999/v2/projects | jq
```

---

## Method 4: Unit Testing

Run tests without starting full IDE:

```bash
# All tests
./gradlew test

# Specific test
./gradlew test --tests "com.morpheus.debugbridge.RestServerTest"

# With more output
./gradlew test --info
```

### Test Files Location
```
src/test/kotlin/com/morpheus/debugbridge/
├── RestServerTest.kt
├── DebugSessionListenerTest.kt
├── DebugStateServiceTest.kt
├── DebugControllerTest.kt
└── ...
```

---

## Creating IntelliJ Run Configurations

### 1. Gradle runIde Configuration
Already exists in Gradle panel: `intellij-debug-bridge > Tasks > intellij platform > runIde`

### 2. Test Configuration
1. Run → Edit Configurations → + → Gradle
2. Name: "Run Tests"
3. Gradle project: intellij-debug-bridge
4. Tasks: `test`

### 3. Build Configuration
1. Run → Edit Configurations → + → Gradle
2. Name: "Build Plugin"
3. Gradle project: intellij-debug-bridge
4. Tasks: `build`

---

## Debugging Tips

### Plugin Not Loading?
Check `plugin.xml`:
```bash
cat src/main/resources/META-INF/plugin.xml
```

Verify:
- Extension points are correct
- Services are declared
- Compatibility range includes your IDE version

### REST Server Not Starting?
1. Check port 19999: `lsof -i:19999`
2. Kill if in use: `lsof -ti:19999 | xargs kill -9`
3. Check logs for startup errors

### EDT Issues?
If debug commands hang:
- Verify you're using `invokeLater`, not direct calls
- Check CountDownLatch timeout isn't too short
- Add logging in EDT block to confirm execution

### State Not Updating?
1. Check `onSessionPaused` is called
2. Verify `DebugSessionListener` is registered
3. Check cache invalidation logic

---

## Postman Collection

Import the included Postman collection for API testing:
```
postman/IntelliJ_Debug_Bridge.postman_collection.json
postman/Debug_Bridge_Local.postman_environment.json
```

---

## Common Gradle Commands

```bash
# Build plugin (JAR in build/libs/)
./gradlew build

# Run IDE sandbox
./gradlew runIde

# Run with debugging
./gradlew runIde --debug-jvm

# Run tests
./gradlew test

# Clean build
./gradlew clean build

# Check for dependency updates
./gradlew dependencyUpdates

# Publish to marketplace (needs credentials)
./gradlew publishPlugin
```

---

## Requirements for Claude Code Integration

To debug this plugin WITH Claude Code using intellij-debugger MCP:

1. **Start sandbox with debug**: `./gradlew runIde`
2. **Open a Java project in sandbox**: Any project with breakpoints
3. **Start debugging in sandbox**: Click Debug on a run config
4. **Use Claude Code**: MCP will connect to `localhost:19999`

The REST API exposed by this plugin is what the MCP server consumes!

---

## Architecture Reminder

```
Your Code Here (intellij-debug-bridge)
     ↓
Compiled to JAR
     ↓
Loaded into IntelliJ (via runIde or installed)
     ↓
Exposes REST API on :19999
     ↓
MCP Server (intellij-debugger) connects
     ↓
Claude Code uses MCP
```

To debug the plugin itself, you debug the SANDBOX IntelliJ, not the MCP server.
