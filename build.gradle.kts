plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

group = "com.morpheus.debugbridge"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // IntelliJ Platform
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("JUnit")  // For JUnit run configurations
    }

    // Ktor for embedded HTTP server
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("io.ktor:ktor-server-cors:2.3.7")

    // Kotlin serialization for JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Note: Coroutines are provided by IntelliJ Platform, do not add explicitly

    // ===== TEST DEPENDENCIES =====
    // JUnit 5
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // MockK for Kotlin mocking
    testImplementation("io.mockk:mockk:1.13.8")

    // Ktor test client
    testImplementation("io.ktor:ktor-server-test-host:2.3.7")
    testImplementation("io.ktor:ktor-client-content-negotiation:2.3.7")

    // Coroutines test
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // AssertJ for fluent assertions
    testImplementation("org.assertj:assertj-core:3.24.2")
}

// Configure Kotlin
kotlin {
    jvmToolchain(21)
}

// Configure IntelliJ Platform Plugin
intellijPlatform {
    pluginConfiguration {
        name = "Debug Bridge for AI"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "243"
            untilBuild = "253.*"
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    // Configure tests to use JUnit 5
    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }

    // Make sure Ktor dependencies are included in the plugin
    prepareSandbox {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(configurations.runtimeClasspath.get().filter {
            it.name.contains("ktor") ||
            it.name.contains("kotlinx-serialization") ||
            it.name.contains("netty") ||
            it.name.contains("slf4j")
        }) {
            into("${project.name}/lib")
        }
    }
}
