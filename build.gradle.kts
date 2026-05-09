plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
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
    maven("https://jitpack.io")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.metrics)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.caching.headers)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.java.jwt)
    implementation(libs.bcrypt)
    implementation(libs.arrow.core)
    implementation(libs.arrow.core.serialization)
    implementation(libs.kotlinx.datetime)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.mockk)
    testImplementation(libs.kotest.extensions.testcontainers)

    detektPlugins(libs.detekt.rules)
    compileOnly(libs.detekt.rules)
}

fun String.runCommand(): String =
    providers
        .exec {
            commandLine(split(" "))
        }.standardOutput
        .asText
        .get()
        .trim()

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

ktlint {
    version.set("1.5.0")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$projectDir/detekt.yml"))
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}
