package io.sdkman.state.support

import arrow.core.Option
import arrow.core.flatMap
import arrow.core.none
import arrow.core.some
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version

fun javaVersion(
    distribution: Distribution,
    version: String,
    platform: Platform = Platform.LINUX_X64,
    visible: Boolean = true,
): Version =
    Version(
        candidate = "java",
        version = version,
        platform = platform,
        url = "https://example.com/java-$version-${distribution.name.lowercase()}-${platform.name.lowercase()}.tar.gz",
        // absent rather than true: a vendor body may not carry visible, and an absent visible is stored visible
        visible = if (visible) none() else false.some(),
        distribution = distribution.some(),
    )

fun javaVendorToken(): String = JwtTestSupport.vendorToken(candidates = listOf("java"))

suspend fun ApplicationTestBuilder.postJavaVersion(
    version: Version,
    token: String = JwtTestSupport.adminToken(),
): HttpResponse = postJavaBody(version.toJavaRouteJsonString(), token)

suspend fun ApplicationTestBuilder.postJavaBody(
    body: String,
    token: String,
): HttpResponse =
    client.post("/versions/java") {
        contentType(ContentType.Application.Json)
        setBody(body)
        bearerAuth(token)
    }

suspend fun ApplicationTestBuilder.deleteJavaVersion(
    version: Version,
    token: String,
): HttpResponse =
    client.delete("/versions/java") {
        contentType(ContentType.Application.Json)
        setBody(version.toJavaUniqueVersionJsonString())
        bearerAuth(token)
    }

fun visibilityOf(version: Version): Option<Boolean> =
    selectVersion(version.candidate, version.version, version.distribution, version.platform).flatMap { it.visible }

fun tagsOf(version: Version): List<String> = selectTagNamesOf(version.candidate, version.version, version.distribution, version.platform)

fun insertTaggedVersion(
    version: Version,
    vararg tags: String,
): Int =
    insertVersionWithId(version).also { versionId ->
        tags.forEach { tag -> insertTag(version.candidate, tag, version.distribution, version.platform, versionId) }
    }
