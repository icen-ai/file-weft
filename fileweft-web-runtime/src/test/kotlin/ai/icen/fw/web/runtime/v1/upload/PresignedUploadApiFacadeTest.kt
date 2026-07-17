package ai.icen.fw.web.runtime.v1.upload

import ai.icen.fw.application.upload.CancelPresignedUploadCommand
import ai.icen.fw.application.upload.CompletePresignedUploadAssetCommand
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimResult
import ai.icen.fw.application.upload.InspectPresignedUploadCommand
import ai.icen.fw.application.upload.PresignedUploadGrantResult
import ai.icen.fw.application.upload.PresignedUploadSessionStatus
import ai.icen.fw.application.upload.PresignedUploadStatusResult
import ai.icen.fw.application.upload.ReissuePresignedUploadCommand
import ai.icen.fw.application.upload.StartPresignedUploadCommand
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.web.api.v1.upload.StartPresignedUploadRequest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PresignedUploadApiFacadeTest {
    @Test
    fun `start validates idempotency and emits only the constrained PUT capability`() {
        val operations = RecordingOperations()
        val request = StartPresignedUploadRequest().apply {
            fileName = "合同.pdf"
            contentLength = 12
            contentType = "application/pdf"
            contentHash = HASH
            checksumAlgorithm = "md5"
            checksumValue = "CY9rzUYh03PK3k6DJie09g=="
        }

        val result = PresignedUploadApiFacade.forTesting(operations).start(listOf("request-1"), request)

        assertEquals("upload-1", result.uploadId)
        assertEquals("PUT", result.method)
        assertEquals("https", result.uploadUrl.scheme)
        assertEquals("application/pdf", result.requiredHeaders["Content-Type"])
        assertFalse(result.requiredHeaders.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertEquals("request-1", operations.callerKey)
        assertEquals(emptyMap(), operations.started?.metadata)
    }

    @Test
    fun `reissue status and cancel validate one opaque path segment`() {
        val operations = RecordingOperations()
        val facade = PresignedUploadApiFacade.forTesting(operations)

        assertEquals("upload-1", facade.reissue("upload-1").uploadId)
        assertEquals("READY", facade.inspect("upload-1").status)
        assertEquals("CANCELLED", facade.cancel("upload-1").status)
        assertFailsWith<IllegalArgumentException> { facade.inspect("../other") }
    }

    @Test
    fun `finalize forwards exact idempotency and returns stable claimed asset ids`() {
        val operations = RecordingOperations()

        val result = PresignedUploadApiFacade.forTesting(operations)
            .finalizeUpload("upload-1", listOf("finalize-1"))

        assertEquals("upload-1", result.uploadId)
        assertEquals("file-object-1", result.fileObjectId)
        assertEquals("file-asset-1", result.fileAssetId)
        assertEquals("finalize-1", operations.finalizeCommand?.idempotencyKey)
    }

    private class RecordingOperations : PresignedUploadFacadeOperations {
        var callerKey: String? = null
        var started: StartPresignedUploadCommand? = null
        var finalizeCommand: CompletePresignedUploadAssetCommand? = null

        override fun startWithCallerKey(
            callerKey: String,
            command: StartPresignedUploadCommand,
        ): PresignedUploadGrantResult {
            this.callerKey = callerKey
            started = command
            return grant(created = true)
        }

        override fun reissue(command: ReissuePresignedUploadCommand): PresignedUploadGrantResult = grant(created = false)

        override fun inspect(command: InspectPresignedUploadCommand): PresignedUploadStatusResult = status(
            PresignedUploadSessionStatus.READY,
        )

        override fun cancel(command: CancelPresignedUploadCommand): PresignedUploadStatusResult = status(
            PresignedUploadSessionStatus.CANCELLED,
        )

        override fun finalize(command: CompletePresignedUploadAssetCommand): CompletedPresignedUploadAssetClaimResult {
            finalizeCommand = command
            return CompletedPresignedUploadAssetClaimResult(
                uploadId = command.uploadId,
                fileObjectId = Identifier("file-object-1"),
                fileAssetId = Identifier("file-asset-1"),
                replayed = false,
            )
        }

        private fun grant(created: Boolean): PresignedUploadGrantResult = PresignedUploadGrantResult(
            sessionId = Identifier("upload-1"),
            uploadUri = URI.create("https://uploads.example/object?signature=redacted"),
            requiredHeaders = mapOf("Content-Type" to "application/pdf"),
            expiresAt = 2_000,
            created = created,
        )

        private fun status(state: PresignedUploadSessionStatus): PresignedUploadStatusResult =
            statusResultConstructor.newInstance(
                Identifier("upload-1"),
                "合同.pdf",
                12L,
                "application/pdf",
                HASH,
                state,
                2_000L,
                3_000L,
                1_000L,
                1_100L,
                null,
                if (state == PresignedUploadSessionStatus.CANCELLED) 1_100L else null,
                null,
            ) as PresignedUploadStatusResult

        private companion object {
            /*
             * The Application module intentionally keeps this result constructor internal so
             * transports cannot manufacture authority-bearing service outcomes. This test only
             * needs a value to exercise DTO mapping, so keep the production boundary closed and
             * construct the value reflectively inside the test fixture.
             */
            val statusResultConstructor = PresignedUploadStatusResult::class.java.declaredConstructors
                .single { it.parameterCount == 13 }
                .apply { isAccessible = true }
        }
    }

    private companion object {
        const val HASH: String = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
