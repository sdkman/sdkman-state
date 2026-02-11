@file:UseSerializers(OptionSerializer::class)

package io.sdkman.domain

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.serialization.OptionSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class Version(
    val candidate: String,
    val version: String,
    val platform: Platform,
    val url: String,
    val visible: Option<Boolean> = None,
    val distribution: Option<Distribution> = None,
    val md5sum: Option<String> = None,
    val sha256sum: Option<String> = None,
    val sha512sum: Option<String> = None,
)

@Serializable
data class UniqueVersion(
    val candidate: String,
    val version: String,
    val distribution: Option<Distribution>,
    val platform: Platform,
)

@Serializable
enum class Distribution {
    BISHENG,
    CORRETTO,
    GRAALCE,
    GRAALVM,
    JETBRAINS,
    KONA,
    LIBERICA,
    LIBERICA_NIK,
    MANDREL,
    MICROSOFT,
    OPENJDK,
    ORACLE,
    SAP_MACHINE,
    SEMERU,
    TEMURIN,
    ZULU,
}

enum class Platform(
    val platformId: String,
) {
    LINUX_X32("linuxx32"),
    LINUX_X64("linuxx64"),
    LINUX_ARM32HF("linuxarm32hf"),
    LINUX_ARM32SF("linuxarm32sf"),
    LINUX_ARM64("linuxarm64"),
    LINUX_RISCV64("linuxriscv64"),
    MAC_X64("darwinx64"),
    MAC_ARM64("darwinarm64"),
    WINDOWS_X64("windowsx64"),
    UNIVERSAL("universal"),
    ;

    companion object {
        fun findByPlatformId(platformId: String): Platform =
            Platform.entries.firstOrNone { it.platformId == platformId }.getOrElse { UNIVERSAL }
    }
}

data class DatabaseFailure(
    override val message: String,
    override val cause: Throwable,
) : Throwable()

enum class HealthStatus {
    SUCCESS,
    FAILURE,
}

interface HealthRepository {
    suspend fun checkDatabaseConnection(): Either<DatabaseFailure, Unit>
}

@Serializable
data class AuditRecord(
    val id: Long = 0,
    val username: String,
    val timestamp: Instant,
    val operation: AuditOperation,
    val versionData: String,
)

@Serializable
enum class AuditOperation {
    CREATE,
    DELETE,
}

interface AuditRepository {
    suspend fun recordAudit(
        username: String,
        operation: AuditOperation,
        version: Version,
    ): Either<DatabaseFailure, Unit>
}
