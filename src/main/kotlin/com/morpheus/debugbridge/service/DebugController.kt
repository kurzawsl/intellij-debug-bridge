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

            // Find module
            val module = if (request.moduleName != null) {
                ModuleManager.getInstance(project).findModuleByName(request.moduleName)
            } else {
                // Auto-detect: use first non-test module or any module
                ModuleManager.getInstance(project).modules.firstOrNull { !it.name.contains("test", ignoreCase = true) }
                    ?: ModuleManager.getInstance(project).modules.firstOrNull()
            }

            if (module == null) {
                return ConfigResponse(false, "No module found. Available modules: ${
                    ModuleManager.getInstance(project).modules.map { it.name }
                }")
            }

            val settings: RunnerAndConfigurationSettings? = when (request.type) {
                ConfigType.JUNIT_CLASS, ConfigType.JUNIT_METHOD -> {
                    createJUnitConfiguration(runManager, request, module)
                }
                ConfigType.APPLICATION -> {
                    createApplicationConfiguration(runManager, request, module)
                }
                ConfigType.SPRING_BOOT -> {
                    createSpringBootConfiguration(runManager, request, module, project)
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

                // Find module
                val module = if (request.moduleName != null) {
                    ModuleManager.getInstance(project).findModuleByName(request.moduleName)
                } else {
                    // Auto-detect: use first non-test module or any module
                    ModuleManager.getInstance(project).modules.firstOrNull { !it.name.contains("test", ignoreCase = true) }
                        ?: ModuleManager.getInstance(project).modules.firstOrNull()
                }

                if (module == null) {
                    result.set(ConfigResponse(false, "No module found. Available modules: ${
                        ModuleManager.getInstance(project).modules.map { it.name }
                    }"))
                    latch.countDown()
                    return@invokeLater
                }

                val settings: RunnerAndConfigurationSettings? = when (request.type) {
                    ConfigType.JUNIT_CLASS, ConfigType.JUNIT_METHOD -> {
                        createJUnitConfiguration(runManager, request, module)
                    }
                    ConfigType.APPLICATION -> {
                        createApplicationConfiguration(runManager, request, module)
                    }
                    ConfigType.SPRING_BOOT -> {
                        createSpringBootConfiguration(runManager, request, module, project)
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
}
