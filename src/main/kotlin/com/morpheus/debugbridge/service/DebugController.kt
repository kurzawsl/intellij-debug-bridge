package com.morpheus.debugbridge.service

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.xdebugger.XDebugSession
import com.morpheus.debugbridge.config.TimeoutConfig
import com.morpheus.debugbridge.model.ConfigResponse
import com.morpheus.debugbridge.model.ConfigType
import com.morpheus.debugbridge.model.CreateConfigRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Controls debug session actions (step, resume, pause, etc.)
 */
object DebugController {

    private val log = Logger.getInstance(DebugController::class.java)

    /**
     * Execute step over in the current debug session
     */
    fun stepOver(): Boolean {
        val session = DebugStateService.getInstance().getActiveSession() ?: return false
        return executeOnSession(session) { it.stepOver(false) }
    }

    /**
     * Execute step into in the current debug session
     */
    fun stepInto(): Boolean {
        val session = DebugStateService.getInstance().getActiveSession() ?: return false
        return executeOnSession(session) { it.stepInto() }
    }

    /**
     * Execute step out in the current debug session
     */
    fun stepOut(): Boolean {
        val session = DebugStateService.getInstance().getActiveSession() ?: return false
        return executeOnSession(session) { it.stepOut() }
    }

    /**
     * Resume execution in the current debug session
     */
    fun resume(): Boolean {
        val session = DebugStateService.getInstance().getActiveSession() ?: return false
        return executeOnSession(session) { it.resume() }
    }

    /**
     * Pause execution in the current debug session
     */
    fun pause(): Boolean {
        val session = DebugStateService.getInstance().getActiveSession() ?: return false
        return executeOnSession(session) { it.pause() }
    }

    /**
     * Run to cursor position (specific line in file)
     */
    fun runToCursor(filePath: String, line: Int): Boolean {
        val session = DebugStateService.getInstance().getActiveSession() ?: return false
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val project = session.project
                val file = FileUtils.findFile(filePath, project)

                if (file == null) {
                    log.warn("File not found: $filePath")
                    latch.countDown()
                    return@invokeLater
                }

                val position = com.intellij.xdebugger.XDebuggerUtil.getInstance()
                    .createPosition(file, line - 1) // 0-based line

                if (position != null) {
                    session.runToPosition(position, false)
                    success.set(true)
                    log.info("Running to cursor at ${file.name}:$line")
                } else {
                    log.warn("Could not create position for ${file.name}:$line")
                }
            } catch (e: Exception) {
                log.error("Failed to run to cursor", e)
            } finally {
                latch.countDown()
            }
        }

        latch.await(TimeoutConfig.RUN_TO_CURSOR_MS, TimeUnit.MILLISECONDS)
        return success.get()
    }

    /**
     * Select a specific stack frame by index
     */
    fun selectFrame(index: Int): Boolean {
        val session = DebugStateService.getInstance().getActiveSession() ?: return false
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val suspendContext = session.suspendContext
                val executionStack = suspendContext?.activeExecutionStack

                if (executionStack == null) {
                    log.warn("No active execution stack")
                    latch.countDown()
                    return@invokeLater
                }

                // Compute stack frames
                val frames = mutableListOf<com.intellij.xdebugger.frame.XStackFrame>()
                val frameLatch = CountDownLatch(1)

                executionStack.computeStackFrames(0, object : com.intellij.xdebugger.frame.XExecutionStack.XStackFrameContainer {
                    override fun addStackFrames(
                        frameList: MutableList<out com.intellij.xdebugger.frame.XStackFrame>,
                        last: Boolean
                    ) {
                        frames.addAll(frameList)
                        if (last) frameLatch.countDown()
                    }

                    override fun errorOccurred(errorMessage: String) {
                        log.warn("Error computing frames: $errorMessage")
                        frameLatch.countDown()
                    }
                })

                if (frameLatch.await(TimeoutConfig.FRAME_OPERATION_MS, TimeUnit.MILLISECONDS) && index < frames.size) {
                    session.setCurrentStackFrame(executionStack, frames[index], false)
                    success.set(true)
                    log.info("Selected frame $index")
                } else {
                    log.warn("Frame index $index out of bounds (${frames.size} frames)")
                }

            } catch (e: Exception) {
                log.error("Failed to select frame", e)
            } finally {
                latch.countDown()
            }
        }

        latch.await(TimeoutConfig.FRAME_OPERATION_MS, TimeUnit.MILLISECONDS)
        return success.get()
    }

    /**
     * Start debugging with the specified or default run configuration
     */
    fun startDebug(configName: String? = null): Boolean {
        val projects = ProjectManager.getInstance().openProjects
        val project = projects.firstOrNull { !it.isDefault }
        return if (project != null) {
            startDebugInProject(project, configName)
        } else {
            log.warn("No open project found")
            false
        }
    }

    /**
     * Start debugging in a specific project with the specified or default run configuration
     */
    fun startDebugInProject(project: Project, configName: String? = null): Boolean {
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val runManager = RunManager.getInstance(project)

                // Get run configuration - either by name or selected
                val settings = if (configName != null) {
                    runManager.allSettings.find { it.name == configName }
                } else {
                    runManager.selectedConfiguration
                }

                if (settings == null) {
                    val availableConfigs = runManager.allSettings.map { it.name }
                    log.warn("No run configuration found${if (configName != null) " named '$configName'" else ""}. " +
                            "Available configurations: $availableConfigs")
                    latch.countDown()
                    return@invokeLater
                }

                // Get the debug executor
                val debugExecutor = ExecutorRegistry.getInstance().getExecutorById(DefaultDebugExecutor.EXECUTOR_ID)
                if (debugExecutor == null) {
                    log.warn("Debug executor not found")
                    latch.countDown()
                    return@invokeLater
                }

                // Start debugging
                log.info("Starting debug in project ${project.name} with configuration: ${settings.name}")
                ProgramRunnerUtil.executeConfiguration(settings, debugExecutor)
                success.set(true)

            } catch (e: Exception) {
                log.error("Failed to start debug", e)
            } finally {
                latch.countDown()
            }
        }

        latch.await(TimeoutConfig.DEBUG_ACTION_MS, TimeUnit.MILLISECONDS)
        return success.get()
    }

    /**
     * Stop the current debug session
     */
    fun stopDebug(): Boolean {
        val session = DebugStateService.getInstance().getActiveSession() ?: return false
        return executeOnSession(session) { it.stop() }
    }

    /**
     * Get list of available run configurations
     */
    fun getRunConfigurations(): List<String> {
        val configs = mutableListOf<String>()

        ApplicationManager.getApplication().invokeAndWait {
            val projects = ProjectManager.getInstance().openProjects
            for (project in projects) {
                if (!project.isDefault) {
                    val runManager = RunManager.getInstance(project)
                    configs.addAll(runManager.allSettings.map { it.name })
                }
            }
        }

        return configs
    }

    /**
     * Execute an action on the debug session
     * Actions must be executed on the appropriate thread
     * FIX: Check for EDT to avoid deadlock when called from EDT
     */
    private fun executeOnSession(session: XDebugSession, action: (XDebugSession) -> Unit): Boolean {
        if (!session.isSuspended && !isResumeAction(action)) {
            log.warn("Cannot execute action: session is not suspended")
            return false
        }

        // FIX: If already on EDT, execute directly to avoid deadlock
        // (invokeLater + latch.await on EDT would deadlock)
        if (ApplicationManager.getApplication().isDispatchThread) {
            return try {
                action(session)
                log.info("Debug action executed on EDT")
                true
            } catch (e: Exception) {
                log.error("Failed to execute debug action on EDT", e)
                false
            }
        }

        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        try {
            // Execute on the EDT (required for debug session operations)
            ApplicationManager.getApplication().invokeLater {
                try {
                    action(session)
                    success.set(true)
                    log.info("Debug action executed successfully")
                } catch (e: Exception) {
                    log.error("Failed to execute debug action", e)
                    success.set(false)
                } finally {
                    latch.countDown()
                }
            }

            // Wait for the action to complete
            if (!latch.await(TimeoutConfig.DEBUG_ACTION_MS, TimeUnit.MILLISECONDS)) {
                log.warn("Debug action timed out after ${TimeoutConfig.DEBUG_ACTION_MS}ms")
                return false
            }

            return success.get()
        } catch (e: Exception) {
            log.error("Error executing debug action", e)
            return false
        }
    }

    /**
     * Check if this is a resume-type action (can run when not suspended)
     */
    private fun isResumeAction(action: (XDebugSession) -> Unit): Boolean {
        // We can't really introspect the lambda, but resume/pause can work in different states
        return true // Allow all actions to attempt execution
    }

    /**
     * Wait for the session to reach a new suspended state after an action.
     * PERFORMANCE: Uses exponential backoff - starts fast, slows down over time
     *
     * Note: This is a BLOCKING call. When calling from coroutines, use
     * withContext(Dispatchers.IO) { waitForSuspend(...) } to avoid blocking
     * the main coroutine dispatcher.
     */
    fun waitForSuspend(timeoutMs: Long = TimeoutConfig.WAIT_FOR_SUSPEND_MS): Boolean {
        val session = DebugStateService.getInstance().getActiveSession() ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        var pollIntervalMs = TimeoutConfig.POLL_INITIAL_MS

        while (System.currentTimeMillis() < deadline) {
            if (session.isSuspended) {
                return true
            }
            try {
                Thread.sleep(pollIntervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return session.isSuspended
            }
            // Exponential backoff with configurable max
            pollIntervalMs = minOf(pollIntervalMs * 2, TimeoutConfig.POLL_MAX_MS)
        }
        return session.isSuspended // Final check
    }

    /**
     * Create a new run configuration in a specific project (V2 API).
     * Must be called from EDT.
     */
    fun createRunConfigurationInProject(project: Project, request: CreateConfigRequest): ConfigResponse {
        return try {
            val runManager = RunManager.getInstance(project)

            // Check if config already exists
            val existing = runManager.allSettings.find { it.name == request.name }
            if (existing != null) {
                return ConfigResponse(true, "Configuration already exists", request.name)
            }

            // Find module (optional for JS/TS projects)
            val module = if (request.moduleName != null) {
                ModuleManager.getInstance(project).findModuleByName(request.moduleName)
            } else {
                // Auto-detect: use first non-test module or any module
                ModuleManager.getInstance(project).modules.firstOrNull { !it.name.contains("test", ignoreCase = true) }
                    ?: ModuleManager.getInstance(project).modules.firstOrNull()
            }

            val settings: RunnerAndConfigurationSettings? = when (request.type) {
                // Java/JVM configurations
                ConfigType.JUNIT_CLASS, ConfigType.JUNIT_METHOD -> {
                    if (module == null) return ConfigResponse(false, "No module found for JUnit config")
                    createJUnitConfiguration(runManager, request, module)
                }
                ConfigType.APPLICATION -> {
                    if (module == null) return ConfigResponse(false, "No module found for Application config")
                    createApplicationConfiguration(runManager, request, module)
                }
                ConfigType.SPRING_BOOT -> {
                    if (module == null) return ConfigResponse(false, "No module found for Spring Boot config")
                    createSpringBootConfiguration(runManager, request, module, project)
                }
                ConfigType.GRADLE -> {
                    createGradleConfiguration(runManager, request, project)
                }
                ConfigType.MAVEN -> {
                    createMavenConfiguration(runManager, request, project)
                }

                // JavaScript/TypeScript configurations
                ConfigType.NPM_SCRIPT -> {
                    createNpmConfiguration(runManager, request, project)
                }
                ConfigType.NODE_JS -> {
                    createNodeJsConfiguration(runManager, request, project)
                }
                ConfigType.VITEST, ConfigType.JEST -> {
                    createJsTestConfiguration(runManager, request, project)
                }

                // Other configurations
                ConfigType.SHELL_SCRIPT -> {
                    createShellScriptConfiguration(runManager, request, project)
                }
                ConfigType.COMPOUND -> {
                    // Compound configs are complex, return unsupported for now
                    return ConfigResponse(false, "Compound configurations not yet supported via API")
                }
            }

            if (settings != null) {
                runManager.addConfiguration(settings)
                runManager.selectedConfiguration = settings
                log.info("Created run configuration in project ${project.name}: ${request.name}")
                ConfigResponse(true, "Configuration created", request.name)
            } else {
                ConfigResponse(false, "Failed to create configuration")
            }

        } catch (e: Exception) {
            log.error("Error creating run configuration in project ${project.name}", e)
            ConfigResponse(false, "Error: ${e.message}")
        }
    }

    /**
     * Create a new run configuration (V1 API - uses first open project)
     */
    fun createRunConfiguration(request: CreateConfigRequest): ConfigResponse {
        val result = AtomicReference<ConfigResponse>()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val projects = ProjectManager.getInstance().openProjects
                val project = projects.firstOrNull { !it.isDefault }

                if (project == null) {
                    result.set(ConfigResponse(false, "No open project found"))
                    latch.countDown()
                    return@invokeLater
                }

                val runManager = RunManager.getInstance(project)

                // Check if config already exists
                val existing = runManager.allSettings.find { it.name == request.name }
                if (existing != null) {
                    result.set(ConfigResponse(true, "Configuration already exists", request.name))
                    latch.countDown()
                    return@invokeLater
                }

                // Find module (only required for JVM configurations)
                val module = if (request.moduleName != null) {
                    ModuleManager.getInstance(project).findModuleByName(request.moduleName)
                } else {
                    // Auto-detect: use first non-test module or any module
                    ModuleManager.getInstance(project).modules.firstOrNull { !it.name.contains("test", ignoreCase = true) }
                        ?: ModuleManager.getInstance(project).modules.firstOrNull()
                }

                // Check module only for JVM types that require it
                val requiresModule = request.type in listOf(
                    ConfigType.JUNIT_CLASS, ConfigType.JUNIT_METHOD,
                    ConfigType.APPLICATION, ConfigType.SPRING_BOOT
                )
                if (requiresModule && module == null) {
                    result.set(ConfigResponse(false, "No module found. Available modules: ${
                        ModuleManager.getInstance(project).modules.map { it.name }
                    }"))
                    latch.countDown()
                    return@invokeLater
                }

                val settings: RunnerAndConfigurationSettings? = when (request.type) {
                    // JVM types require module (already verified non-null above)
                    ConfigType.JUNIT_CLASS, ConfigType.JUNIT_METHOD -> {
                        createJUnitConfiguration(runManager, request, module!!)
                    }
                    ConfigType.APPLICATION -> {
                        createApplicationConfiguration(runManager, request, module!!)
                    }
                    ConfigType.SPRING_BOOT -> {
                        createSpringBootConfiguration(runManager, request, module!!, project)
                    }
                    // Non-JVM types: don't require module
                    ConfigType.GRADLE -> createGradleConfiguration(runManager, request, project)
                    ConfigType.MAVEN -> createMavenConfiguration(runManager, request, project)
                    ConfigType.NPM_SCRIPT -> createNpmConfiguration(runManager, request, project)
                    ConfigType.NODE_JS -> createNodeJsConfiguration(runManager, request, project)
                    ConfigType.VITEST, ConfigType.JEST -> createJsTestConfiguration(runManager, request, project)
                    ConfigType.SHELL_SCRIPT -> createShellScriptConfiguration(runManager, request, project)
                    ConfigType.COMPOUND -> {
                        result.set(ConfigResponse(false, "Compound configurations not yet supported"))
                        latch.countDown()
                        return@invokeLater
                    }
                }

                if (settings != null) {
                    runManager.addConfiguration(settings)
                    runManager.selectedConfiguration = settings
                    log.info("Created run configuration: ${request.name}")
                    result.set(ConfigResponse(true, "Configuration created", request.name))
                } else {
                    result.set(ConfigResponse(false, "Failed to create configuration"))
                }

            } catch (e: Exception) {
                log.error("Error creating run configuration", e)
                result.set(ConfigResponse(false, "Error: ${e.message}"))
            } finally {
                latch.countDown()
            }
        }

        latch.await(TimeoutConfig.DEBUG_ACTION_MS, TimeUnit.MILLISECONDS)
        return result.get() ?: ConfigResponse(false, "Timeout creating configuration")
    }

    private fun createJUnitConfiguration(
        runManager: RunManager,
        request: CreateConfigRequest,
        module: com.intellij.openapi.module.Module
    ): RunnerAndConfigurationSettings? {
        val configurationType = JUnitConfigurationType.getInstance()
        val factory = configurationType.configurationFactories.firstOrNull() ?: return null

        val settings = runManager.createConfiguration(request.name, factory)
        val configuration = settings.configuration as? JUnitConfiguration ?: return null

        configuration.setModule(module)

        // Get the persistent data using reflection or direct access
        val data = configuration.persistentData

        if (request.type == ConfigType.JUNIT_METHOD && request.methodName != null) {
            // Run single test method
            data.TEST_OBJECT = JUnitConfiguration.TEST_METHOD
            data.MAIN_CLASS_NAME = request.className
            data.METHOD_NAME = request.methodName
        } else {
            // Run all tests in class
            data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS
            data.MAIN_CLASS_NAME = request.className
        }

        // Set VM options if provided
        if (!request.vmOptions.isNullOrBlank()) {
            configuration.setVMParameters(request.vmOptions)
        }

        // Set program arguments if provided
        if (!request.programArguments.isNullOrBlank()) {
            configuration.setProgramParameters(request.programArguments)
        }

        log.info("Created JUnit configuration for ${request.className}${if (request.methodName != null) ".${request.methodName}" else ""}")
        return settings
    }

    private fun createApplicationConfiguration(
        runManager: RunManager,
        request: CreateConfigRequest,
        module: com.intellij.openapi.module.Module
    ): RunnerAndConfigurationSettings? {
        val configurationType = ApplicationConfigurationType.getInstance()
        val factory = configurationType.configurationFactories.firstOrNull() ?: return null

        val settings = runManager.createConfiguration(request.name, factory)
        val configuration = settings.configuration as? ApplicationConfiguration ?: return null

        configuration.setModule(module)
        configuration.setMainClassName(request.className)

        // Set VM options if provided
        if (!request.vmOptions.isNullOrBlank()) {
            configuration.setVMParameters(request.vmOptions)
        }

        // Set program arguments if provided
        if (!request.programArguments.isNullOrBlank()) {
            configuration.setProgramParameters(request.programArguments)
        }

        log.info("Created Application configuration for ${request.className}")
        return settings
    }

    /**
     * Create a Spring Boot run configuration with active profiles and AWS settings.
     * Uses reflection to access Spring Boot plugin APIs (to avoid hard dependency).
     */
    private fun createSpringBootConfiguration(
        runManager: RunManager,
        request: CreateConfigRequest,
        module: com.intellij.openapi.module.Module,
        project: com.intellij.openapi.project.Project
    ): RunnerAndConfigurationSettings? {
        try {
            // Try to find Spring Boot configuration type via reflection
            val springBootConfigType = findSpringBootConfigurationType()

            if (springBootConfigType != null) {
                val factory = springBootConfigType.configurationFactories.firstOrNull() ?: return null
                val settings = runManager.createConfiguration(request.name, factory)
                val configuration = settings.configuration

                // Set module using reflection (Spring Boot config has setModule)
                try {
                    val setModuleMethod = configuration.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                    setModuleMethod.invoke(configuration, module)
                } catch (e: NoSuchMethodException) {
                    log.debug("setModule not found, trying alternative approach")
                }

                // Set main class using reflection
                try {
                    val setMainClassNameMethod = configuration.javaClass.getMethod("setMainClassName", String::class.java)
                    setMainClassNameMethod.invoke(configuration, request.className)
                } catch (e: NoSuchMethodException) {
                    // Try alternative method name
                    try {
                        val setMainClassMethod = configuration.javaClass.getMethod("setMainClass", String::class.java)
                        setMainClassMethod.invoke(configuration, request.className)
                    } catch (e2: Exception) {
                        log.warn("Could not set main class: ${e2.message}")
                    }
                }

                // Set active profiles using reflection
                if (!request.activeProfiles.isNullOrBlank()) {
                    try {
                        val setActiveProfilesMethod = configuration.javaClass.getMethod("setActiveProfiles", String::class.java)
                        setActiveProfilesMethod.invoke(configuration, request.activeProfiles)
                        log.info("Set active profiles: ${request.activeProfiles}")
                    } catch (e: NoSuchMethodException) {
                        // Try via Spring Boot options map
                        try {
                            val getOptionsMethod = configuration.javaClass.getMethod("getSpringBootOptions")
                            val options = getOptionsMethod.invoke(configuration) as? MutableMap<String, String>
                            options?.put("spring.profiles.active", request.activeProfiles)
                            log.info("Set active profiles via options map: ${request.activeProfiles}")
                        } catch (e2: Exception) {
                            // Fall back to VM options
                            log.warn("Could not set active profiles directly, adding to VM options")
                        }
                    }
                }

                // Set AWS credentials if provided
                if (!request.awsProfile.isNullOrBlank()) {
                    configureAwsConnection(configuration, request.awsProfile, request.awsRegion)
                }

                // Set VM options if provided
                if (!request.vmOptions.isNullOrBlank()) {
                    try {
                        val setVmOptionsMethod = configuration.javaClass.getMethod("setVMParameters", String::class.java)
                        setVmOptionsMethod.invoke(configuration, request.vmOptions)
                    } catch (e: Exception) {
                        log.warn("Could not set VM options: ${e.message}")
                    }
                }

                // Set program arguments if provided
                if (!request.programArguments.isNullOrBlank()) {
                    try {
                        val setProgramParamsMethod = configuration.javaClass.getMethod("setProgramParameters", String::class.java)
                        setProgramParamsMethod.invoke(configuration, request.programArguments)
                    } catch (e: Exception) {
                        log.warn("Could not set program arguments: ${e.message}")
                    }
                }

                log.info("Created Spring Boot configuration: ${request.name} with profiles: ${request.activeProfiles}, AWS: ${request.awsProfile}")
                return settings
            } else {
                // Fall back to Application configuration with Spring Boot-like setup
                log.warn("Spring Boot plugin not found, creating Application configuration with Spring Boot VM options")
                return createSpringBootFallbackConfiguration(runManager, request, module)
            }

        } catch (e: Exception) {
            log.error("Error creating Spring Boot configuration", e)
            // Try fallback
            return createSpringBootFallbackConfiguration(runManager, request, module)
        }
    }

    /**
     * Find Spring Boot configuration type using reflection.
     */
    private fun findSpringBootConfigurationType(): com.intellij.execution.configurations.ConfigurationType? {
        try {
            // Try the main Spring Boot config type class
            val springBootClass = Class.forName("com.intellij.spring.boot.run.SpringBootApplicationConfigurationType")
            val getInstanceMethod = springBootClass.getMethod("getInstance")
            return getInstanceMethod.invoke(null) as? com.intellij.execution.configurations.ConfigurationType
        } catch (e: ClassNotFoundException) {
            log.debug("Spring Boot configuration type not found: ${e.message}")
        } catch (e: Exception) {
            log.warn("Error finding Spring Boot configuration type: ${e.message}")
        }

        // Try alternative class names used by different plugin versions
        for (className in listOf(
            "com.intellij.spring.boot.run.SpringBootConfigurationType",
            "org.jetbrains.idea.spring.boot.run.SpringBootApplicationConfigurationType"
        )) {
            try {
                val springBootClass = Class.forName(className)
                val getInstanceMethod = springBootClass.getMethod("getInstance")
                return getInstanceMethod.invoke(null) as? com.intellij.execution.configurations.ConfigurationType
            } catch (e: Exception) {
                log.debug("Alternative Spring Boot type $className not found")
            }
        }

        return null
    }

    /**
     * Configure AWS connection for Spring Boot configuration using reflection.
     */
    private fun configureAwsConnection(configuration: Any, awsProfile: String, awsRegion: String?) {
        try {
            // Try to find and configure AWS connection
            // The AWS Toolkit plugin uses AwsConnectionOptions or similar
            val setAwsProfileMethod = configuration.javaClass.methods.find {
                it.name.contains("AwsProfile", ignoreCase = true) ||
                it.name.contains("AwsConnection", ignoreCase = true) ||
                it.name.contains("CredentialProfile", ignoreCase = true)
            }

            if (setAwsProfileMethod != null) {
                if (setAwsProfileMethod.parameterCount == 1) {
                    setAwsProfileMethod.invoke(configuration, awsProfile)
                    log.info("Set AWS profile: $awsProfile")
                }
            } else {
                // Try setting via environment variables approach
                try {
                    val getEnvVarsMethod = configuration.javaClass.getMethod("getEnvs")
                    @Suppress("UNCHECKED_CAST")
                    val envVars = getEnvVarsMethod.invoke(configuration) as? MutableMap<String, String>
                    envVars?.put("AWS_PROFILE", awsProfile)
                    if (!awsRegion.isNullOrBlank()) {
                        envVars?.put("AWS_REGION", awsRegion)
                        envVars?.put("AWS_DEFAULT_REGION", awsRegion)
                    }
                    log.info("Set AWS profile via environment variables: $awsProfile, region: $awsRegion")
                } catch (e: Exception) {
                    log.debug("Could not set AWS via environment: ${e.message}")
                }
            }

            // Set AWS region if provided
            if (!awsRegion.isNullOrBlank()) {
                val setAwsRegionMethod = configuration.javaClass.methods.find {
                    it.name.contains("AwsRegion", ignoreCase = true) ||
                    it.name.contains("Region", ignoreCase = true)
                }
                if (setAwsRegionMethod != null && setAwsRegionMethod.parameterCount == 1) {
                    try {
                        setAwsRegionMethod.invoke(configuration, awsRegion)
                        log.info("Set AWS region: $awsRegion")
                    } catch (e: Exception) {
                        log.debug("Could not set AWS region via ${setAwsRegionMethod.name}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Error configuring AWS connection: ${e.message}")
        }
    }

    /**
     * Create a fallback Application configuration with Spring Boot-like setup.
     * Used when Spring Boot plugin is not available.
     */
    private fun createSpringBootFallbackConfiguration(
        runManager: RunManager,
        request: CreateConfigRequest,
        module: com.intellij.openapi.module.Module
    ): RunnerAndConfigurationSettings? {
        val configurationType = ApplicationConfigurationType.getInstance()
        val factory = configurationType.configurationFactories.firstOrNull() ?: return null

        val settings = runManager.createConfiguration(request.name, factory)
        val configuration = settings.configuration as? ApplicationConfiguration ?: return null

        configuration.setModule(module)
        configuration.setMainClassName(request.className)

        // Build VM options with Spring Boot specific settings
        val vmOptions = buildString {
            // Add active profiles via system property
            if (!request.activeProfiles.isNullOrBlank()) {
                append("-Dspring.profiles.active=${request.activeProfiles} ")
            }

            // Add AWS credentials via system properties
            if (!request.awsProfile.isNullOrBlank()) {
                append("-Daws.profile=${request.awsProfile} ")
            }
            if (!request.awsRegion.isNullOrBlank()) {
                append("-Daws.region=${request.awsRegion} ")
            }

            // Add user-provided VM options
            if (!request.vmOptions.isNullOrBlank()) {
                append(request.vmOptions)
            }
        }.trim()

        if (vmOptions.isNotBlank()) {
            configuration.setVMParameters(vmOptions)
        }

        // Set program arguments if provided
        if (!request.programArguments.isNullOrBlank()) {
            configuration.setProgramParameters(request.programArguments)
        }

        // Set environment variables for AWS
        if (!request.awsProfile.isNullOrBlank() || !request.awsRegion.isNullOrBlank()) {
            val envVars = configuration.envs.toMutableMap()
            if (!request.awsProfile.isNullOrBlank()) {
                envVars["AWS_PROFILE"] = request.awsProfile
            }
            if (!request.awsRegion.isNullOrBlank()) {
                envVars["AWS_REGION"] = request.awsRegion
                envVars["AWS_DEFAULT_REGION"] = request.awsRegion
            }
            configuration.setEnvs(envVars)
        }

        log.info("Created fallback Application configuration for Spring Boot: ${request.name}")
        return settings
    }

    /**
     * Delete a run configuration by name
     */
    fun deleteRunConfiguration(name: String): ConfigResponse {
        val result = AtomicReference<ConfigResponse>()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val projects = ProjectManager.getInstance().openProjects
                val project = projects.firstOrNull { !it.isDefault }

                if (project == null) {
                    result.set(ConfigResponse(false, "No open project found"))
                    latch.countDown()
                    return@invokeLater
                }

                val runManager = RunManager.getInstance(project)
                val settings = runManager.allSettings.find { it.name == name }

                if (settings != null) {
                    runManager.removeConfiguration(settings)
                    log.info("Deleted run configuration: $name")
                    result.set(ConfigResponse(true, "Configuration deleted", name))
                } else {
                    val available = runManager.allSettings.map { it.name }
                    result.set(ConfigResponse(false, "Configuration not found", null, available))
                }

            } catch (e: Exception) {
                log.error("Error deleting run configuration", e)
                result.set(ConfigResponse(false, "Error: ${e.message}"))
            } finally {
                latch.countDown()
            }
        }

        latch.await(TimeoutConfig.DEBUG_ACTION_MS, TimeUnit.MILLISECONDS)
        return result.get() ?: ConfigResponse(false, "Timeout deleting configuration")
    }

    /**
     * Get list of available modules in the project
     */
    fun getModules(): List<String> {
        val modules = mutableListOf<String>()

        ApplicationManager.getApplication().invokeAndWait {
            val projects = ProjectManager.getInstance().openProjects
            for (project in projects) {
                if (!project.isDefault) {
                    modules.addAll(ModuleManager.getInstance(project).modules.map { it.name })
                }
            }
        }

        return modules
    }

    // ==================== JavaScript/TypeScript Configuration Methods ====================

    /**
     * Create an npm script configuration.
     * Uses reflection to access Node.js plugin APIs.
     */
    private fun createNpmConfiguration(
        runManager: RunManager,
        request: CreateConfigRequest,
        project: Project
    ): RunnerAndConfigurationSettings? {
        try {
            // Try to find npm configuration type
            val npmConfigType = findConfigurationTypeById("js.build_tools.npm")
                ?: return createGenericScriptConfiguration(runManager, request, project, "npm")

            val factory = npmConfigType.configurationFactories.firstOrNull() ?: return null
            val settings = runManager.createConfiguration(request.name, factory)
            val configuration = settings.configuration

            // Set npm script via reflection
            setPropertyViaReflection(configuration, "runSettings") { runSettings ->
                setPropertyViaReflection(runSettings, "scriptName", request.scriptName ?: "start")
                setPropertyViaReflection(runSettings, "arguments", request.programArguments ?: "")
                if (!request.workingDirectory.isNullOrBlank()) {
                    setPropertyViaReflection(runSettings, "workingDir", request.workingDirectory)
                }
            }

            // Set environment variables
            setEnvVariables(configuration, request.envVariables)

            log.info("Created npm configuration: ${request.name} with script: ${request.scriptName}")
            return settings
        } catch (e: Exception) {
            log.warn("Failed to create npm configuration, falling back to shell: ${e.message}")
            return createGenericScriptConfiguration(runManager, request, project, "npm")
        }
    }

    /**
     * Create a Node.js run configuration.
     */
    private fun createNodeJsConfiguration(
        runManager: RunManager,
        request: CreateConfigRequest,
        project: Project
    ): RunnerAndConfigurationSettings? {
        try {
            val nodeConfigType = findConfigurationTypeById("NodeJSConfigurationType")
                ?: return createGenericScriptConfiguration(runManager, request, project, "node")

            val factory = nodeConfigType.configurationFactories.firstOrNull() ?: return null
            val settings = runManager.createConfiguration(request.name, factory)
            val configuration = settings.configuration

            // Set Node.js file path
            setPropertyViaReflection(configuration, "inputPath", request.scriptPath ?: "")

            // Set Node.js options
            if (!request.nodeOptions.isNullOrBlank()) {
                setPropertyViaReflection(configuration, "nodeOptions", request.nodeOptions)
            }

            // Set working directory
            if (!request.workingDirectory.isNullOrBlank()) {
                setPropertyViaReflection(configuration, "workingDirectory", request.workingDirectory)
            }

            // Set environment variables
            setEnvVariables(configuration, request.envVariables)

            log.info("Created Node.js configuration: ${request.name} for file: ${request.scriptPath}")
            return settings
        } catch (e: Exception) {
            log.warn("Failed to create Node.js configuration, falling back to shell: ${e.message}")
            return createGenericScriptConfiguration(runManager, request, project, "node")
        }
    }

    /**
     * Create a Vitest or Jest test configuration.
     */
    private fun createJsTestConfiguration(
        runManager: RunManager,
        request: CreateConfigRequest,
        project: Project
    ): RunnerAndConfigurationSettings? {
        val isVitest = request.type == ConfigType.VITEST
        val testRunner = if (isVitest) "vitest" else "jest"

        try {
            // Try to find dedicated test runner config type
            val configTypeId = if (isVitest) "vitest-run-configuration-type" else "JavaScriptTestRunnerJest"
            val testConfigType = findConfigurationTypeById(configTypeId)

            if (testConfigType != null) {
                val factory = testConfigType.configurationFactories.firstOrNull() ?: return null
                val settings = runManager.createConfiguration(request.name, factory)
                val configuration = settings.configuration

                // Set test pattern if provided
                if (!request.testPattern.isNullOrBlank()) {
                    setPropertyViaReflection(configuration, "testPattern", request.testPattern)
                }

                // Set working directory
                if (!request.workingDirectory.isNullOrBlank()) {
                    setPropertyViaReflection(configuration, "workingDirectory", request.workingDirectory)
                }

                // Set environment variables
                setEnvVariables(configuration, request.envVariables)

                log.info("Created $testRunner configuration: ${request.name}")
                return settings
            }

            // Fallback: create as npm script running the test command
            val npmRequest = request.copy(
                scriptName = testRunner,
                programArguments = buildString {
                    if (!request.testPattern.isNullOrBlank()) {
                        append(request.testPattern)
                    }
                    if (!request.programArguments.isNullOrBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(request.programArguments)
                    }
                }.ifBlank { null }
            )
            return createNpmConfiguration(runManager, npmRequest, project)

        } catch (e: Exception) {
            log.warn("Failed to create $testRunner configuration: ${e.message}")
            return createGenericScriptConfiguration(runManager, request, project, testRunner)
        }
    }

    // ==================== Build Tool Configuration Methods ====================

    /**
     * Create a Gradle run configuration.
     */
    private fun createGradleConfiguration(
        runManager: RunManager,
        request: CreateConfigRequest,
        project: Project
    ): RunnerAndConfigurationSettings? {
        try {
            val gradleConfigType = findConfigurationTypeById("GradleRunConfiguration")
                ?: return createGenericScriptConfiguration(runManager, request, project, "./gradlew")

            val factory = gradleConfigType.configurationFactories.firstOrNull() ?: return null
            val settings = runManager.createConfiguration(request.name, factory)
            val configuration = settings.configuration

            // Set Gradle task
            val taskName = request.gradleTask ?: "build"
            setPropertyViaReflection(configuration, "settings") { gradleSettings ->
                setPropertyViaReflection(gradleSettings, "taskNames", listOf(taskName))
                setPropertyViaReflection(gradleSettings, "externalProjectPath", project.basePath ?: "")
            }

            // Set VM options
            if (!request.vmOptions.isNullOrBlank()) {
                setPropertyViaReflection(configuration, "vmOptions", request.vmOptions)
            }

            log.info("Created Gradle configuration: ${request.name} for task: $taskName")
            return settings
        } catch (e: Exception) {
            log.warn("Failed to create Gradle configuration, falling back to shell: ${e.message}")
            return createGenericScriptConfiguration(runManager, request, project, "./gradlew")
        }
    }

    /**
     * Create a Maven run configuration.
     */
    private fun createMavenConfiguration(
        runManager: RunManager,
        request: CreateConfigRequest,
        project: Project
    ): RunnerAndConfigurationSettings? {
        try {
            val mavenConfigType = findConfigurationTypeById("MavenRunConfiguration")
                ?: return createGenericScriptConfiguration(runManager, request, project, "./mvnw")

            val factory = mavenConfigType.configurationFactories.firstOrNull() ?: return null
            val settings = runManager.createConfiguration(request.name, factory)
            val configuration = settings.configuration

            // Set Maven goal
            val goal = request.mavenGoal ?: "package"
            setPropertyViaReflection(configuration, "goals", goal)
            setPropertyViaReflection(configuration, "workingDirectory", project.basePath ?: "")

            // Set VM options
            if (!request.vmOptions.isNullOrBlank()) {
                setPropertyViaReflection(configuration, "vmOptions", request.vmOptions)
            }

            log.info("Created Maven configuration: ${request.name} for goal: $goal")
            return settings
        } catch (e: Exception) {
            log.warn("Failed to create Maven configuration, falling back to shell: ${e.message}")
            return createGenericScriptConfiguration(runManager, request, project, "./mvnw")
        }
    }

    /**
     * Create a shell script configuration.
     */
    private fun createShellScriptConfiguration(
        runManager: RunManager,
        request: CreateConfigRequest,
        project: Project
    ): RunnerAndConfigurationSettings? {
        try {
            val shellConfigType = findConfigurationTypeById("ShConfigurationType")
                ?: return null

            val factory = shellConfigType.configurationFactories.firstOrNull() ?: return null
            val settings = runManager.createConfiguration(request.name, factory)
            val configuration = settings.configuration

            // Set script path
            setPropertyViaReflection(configuration, "scriptPath", request.scriptPath ?: "")

            // Set working directory
            if (!request.workingDirectory.isNullOrBlank()) {
                setPropertyViaReflection(configuration, "scriptWorkingDirectory", request.workingDirectory)
            } else {
                setPropertyViaReflection(configuration, "scriptWorkingDirectory", project.basePath ?: "")
            }

            // Set environment variables
            setEnvVariables(configuration, request.envVariables)

            log.info("Created shell script configuration: ${request.name}")
            return settings
        } catch (e: Exception) {
            log.warn("Failed to create shell script configuration: ${e.message}")
            return null
        }
    }

    // ==================== Helper Methods for Generic Configuration ====================

    /**
     * Create a generic script configuration as fallback.
     * Uses shell script type to run the command.
     */
    private fun createGenericScriptConfiguration(
        runManager: RunManager,
        request: CreateConfigRequest,
        project: Project,
        command: String
    ): RunnerAndConfigurationSettings? {
        try {
            val shellConfigType = findConfigurationTypeById("ShConfigurationType")
            if (shellConfigType == null) {
                log.warn("Shell configuration type not available")
                return null
            }

            val factory = shellConfigType.configurationFactories.firstOrNull() ?: return null
            val settings = runManager.createConfiguration(request.name, factory)
            val configuration = settings.configuration

            // Build the command line
            val fullCommand = buildString {
                append(command)
                when (request.type) {
                    ConfigType.NPM_SCRIPT -> {
                        append(" run ")
                        append(request.scriptName ?: "start")
                    }
                    ConfigType.NODE_JS -> {
                        append(" ")
                        append(request.scriptPath ?: "index.js")
                    }
                    ConfigType.VITEST, ConfigType.JEST -> {
                        if (!request.testPattern.isNullOrBlank()) {
                            append(" ")
                            append(request.testPattern)
                        }
                    }
                    ConfigType.GRADLE -> {
                        append(" ")
                        append(request.gradleTask ?: "build")
                    }
                    ConfigType.MAVEN -> {
                        append(" ")
                        append(request.mavenGoal ?: "package")
                    }
                    else -> {}
                }
                if (!request.programArguments.isNullOrBlank()) {
                    append(" ")
                    append(request.programArguments)
                }
            }

            // Set as inline script
            setPropertyViaReflection(configuration, "scriptText", fullCommand)
            setPropertyViaReflection(configuration, "executeScriptFile", false)

            // Set working directory
            val workDir = request.workingDirectory ?: project.basePath ?: ""
            setPropertyViaReflection(configuration, "scriptWorkingDirectory", workDir)

            // Set environment variables
            setEnvVariables(configuration, request.envVariables)

            log.info("Created generic script configuration: ${request.name} with command: $fullCommand")
            return settings
        } catch (e: Exception) {
            log.error("Failed to create generic script configuration: ${e.message}", e)
            return null
        }
    }

    /**
     * Find a configuration type by its ID using reflection.
     */
    private fun findConfigurationTypeById(typeId: String): com.intellij.execution.configurations.ConfigurationType? {
        return try {
            val allTypes = com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
            allTypes.find { it.id == typeId || it.id.contains(typeId, ignoreCase = true) }
        } catch (e: Exception) {
            log.debug("Configuration type $typeId not found: ${e.message}")
            null
        }
    }

    /**
     * Set a property on an object via reflection.
     */
    private fun setPropertyViaReflection(obj: Any, propertyName: String, value: Any?) {
        if (value == null) return
        try {
            // Try setter method first
            val setterName = "set${propertyName.replaceFirstChar { it.uppercase() }}"
            val setter = obj.javaClass.methods.find { it.name == setterName && it.parameterCount == 1 }
            if (setter != null) {
                setter.invoke(obj, value)
                return
            }

            // Try direct field access
            val field = obj.javaClass.declaredFields.find { it.name == propertyName }
            if (field != null) {
                field.isAccessible = true
                field.set(obj, value)
            }
        } catch (e: Exception) {
            log.debug("Failed to set property $propertyName: ${e.message}")
        }
    }

    /**
     * Set a property and then configure its nested object.
     */
    private fun setPropertyViaReflection(obj: Any, propertyName: String, configure: (Any) -> Unit) {
        try {
            // Get the property
            val getterName = "get${propertyName.replaceFirstChar { it.uppercase() }}"
            val getter = obj.javaClass.methods.find { it.name == getterName && it.parameterCount == 0 }
            if (getter != null) {
                val nestedObj = getter.invoke(obj)
                if (nestedObj != null) {
                    configure(nestedObj)
                }
            }
        } catch (e: Exception) {
            log.debug("Failed to get and configure property $propertyName: ${e.message}")
        }
    }

    /**
     * Set environment variables on a configuration.
     */
    private fun setEnvVariables(configuration: Any, envVars: Map<String, String>?) {
        if (envVars.isNullOrEmpty()) return
        try {
            val getEnvsMethod = configuration.javaClass.getMethod("getEnvs")
            @Suppress("UNCHECKED_CAST")
            val existingEnvs = (getEnvsMethod.invoke(configuration) as? Map<String, String>)?.toMutableMap()
                ?: mutableMapOf()
            existingEnvs.putAll(envVars)

            val setEnvsMethod = configuration.javaClass.getMethod("setEnvs", Map::class.java)
            setEnvsMethod.invoke(configuration, existingEnvs)
        } catch (e: Exception) {
            log.debug("Failed to set environment variables: ${e.message}")
        }
    }
}
