import java.io.ByteArrayOutputStream

val ktor_version: String by project
val logback_version: String by project
val postgres_version: String by project
val flyway_version: String by project
val exposed_version: String by project
val kotest_version: String by project

plugins {
    kotlin("jvm") version "1.9.22"
    id("io.ktor.plugin") version "2.3.7"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

group = "io.sdkman"
version = "0.0.1"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-compression-jvm")
    implementation("io.ktor:ktor-server-swagger-jvm")
    implementation("io.ktor:ktor-server-metrics-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("org.postgresql:postgresql:$postgres_version")
    implementation("org.flywaydb:flyway-core:$flyway_version")

    testImplementation("io.ktor:ktor-server-tests-jvm")
    testImplementation("io.kotest:kotest-assertions-core:$kotest_version")
    testImplementation("io.kotest:kotest-runner-junit5:$kotest_version")
}

fun String.runCommand(): String =
    with(ByteArrayOutputStream()) baos@{
        project.exec {
            commandLine = split(" ")
            standardOutput = this@baos
        }
        toString().trim()
    }

ktor {
    docker {
        localImageName.set("registry.digitalocean.com/sdkman/sdkman-state")
        imageTag.set("git rev-parse --short=8 HEAD".runCommand())
        jreVersion.set(JavaVersion.VERSION_21)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
