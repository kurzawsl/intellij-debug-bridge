plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.2"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

group = "com.morpheus.debugbridge"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor for embedded HTTP server
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("io.ktor:ktor-server-cors:2.3.7")

    // Kotlin serialization for JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

// Configure Gradle IntelliJ Plugin
intellij {
    version.set("2024.1")
    type.set("IC") // IntelliJ IDEA Community Edition

    // Required plugins for debugging support
    plugins.set(listOf(
        "com.intellij.java"  // Java debugging support
    ))
}

tasks {
    // Set JVM compatibility
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("243.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
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
