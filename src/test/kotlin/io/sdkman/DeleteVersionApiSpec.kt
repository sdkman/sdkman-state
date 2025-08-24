package io.sdkman

import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.sdkman.domain.Platform
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import io.sdkman.support.*

// testuser:password123 base64 encoded
private const val BasicAuthHeader = "Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM="

//TODO: Add a test for deleting a version for a candidate with platform and NO vendor
//TODO: Add a test for UniqueVersion validation failure
class DeleteVersionApiSpec : ShouldSpec({

    should("DELETE a version for a candidate, platform and vendor") {
        val candidate = "java"
        val version = "17.0.1"
        val vendor = "temurin"
        val platform = Platform.MAC_X64

        val requestBody = UniqueVersion(
            candidate = candidate,
            version = version,
            vendor = vendor.some(),
            platform = platform,
        ).toJsonString()

        withCleanDatabase {
            insertVersions(
                Version(
                    candidate = candidate,
                    version = version,
                    platform = platform,
                    url = "https://java-17.0.1-tem",
                    visible = true,
                    vendor = vendor.some(),
                    md5sum = "3bc0c1d7b4805831680ee5a8690ebb6e".some()
                )
            )
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.NoContent
            }
            selectVersion(candidate, version, vendor.some(), platform) shouldBe None
        }
    }
})