package ai.icen.fw.web.api

import ai.icen.fw.web.api.v1.upload.PresignedUploadGrantDto
import ai.icen.fw.web.api.v1.upload.StartPresignedUploadCommand
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PresignedUploadApiContractTest {
    @Test
    fun `copies required headers and rejects credential-bearing capabilities`() {
        val source = linkedMapOf("Content-Type" to "application/pdf")
        val grant = PresignedUploadGrantDto(
            "upload-1",
            URI.create("https://uploads.example/object?signature=opaque"),
            source,
            2_000,
            true,
        )

        source["Content-Type"] = "text/plain"

        assertEquals("application/pdf", grant.requiredHeaders["Content-Type"])
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (grant.requiredHeaders as MutableMap<String, String>)["X-Test"] = "value"
        }
        assertFailsWith<IllegalArgumentException> {
            PresignedUploadGrantDto(
                "upload-1",
                URI.create("https://uploads.example/object?signature=opaque"),
                mapOf("X-Oss-Security-Token" to "temporary-secret"),
                2_000,
                true,
            )
        }
    }

    @Test
    fun `rejects unsafe transport declarations before they reach application code`() {
        assertFailsWith<IllegalArgumentException> {
            PresignedUploadGrantDto(
                "upload-1",
                URI.create("http://uploads.example/object?signature=opaque"),
                emptyMap(),
                2_000,
                true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PresignedUploadGrantDto(
                "upload-1",
                URI.create("https://uploads.example/object?signature=opaque"),
                linkedMapOf("Content-Type" to "application/pdf", "content-type" to "text/plain"),
                2_000,
                true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StartPresignedUploadCommand(
                "../contract.pdf",
                12,
                "application/pdf",
                "sha256:${"a".repeat(64)}",
                "md5",
                "CY9rzUYh03PK3k6DJie09g==",
            )
        }
    }
}
