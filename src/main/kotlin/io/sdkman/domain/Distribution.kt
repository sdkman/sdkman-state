package io.sdkman.domain

import kotlinx.serialization.Serializable

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
