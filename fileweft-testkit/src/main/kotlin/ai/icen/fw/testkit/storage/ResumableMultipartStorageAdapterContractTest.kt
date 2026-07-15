package ai.icen.fw.testkit.storage

import ai.icen.fw.spi.storage.ResumableMultipartStorageAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/** Contract for recovering a provider's authoritative multipart part ledger. */
abstract class ResumableMultipartStorageAdapterContractTest : StorageAdapterContractTest() {
    protected open val resumableMultipartStorageAdapter: ResumableMultipartStorageAdapter
        get() = storageAdapter as? ResumableMultipartStorageAdapter
            ?: error("Multipart recovery contract requires ResumableMultipartStorageAdapter.")

    @Test
    fun `lists the current server acknowledgements and rejects an aborted upload`() {
        val parts = multipartParts()
        require(parts.isNotEmpty()) { "Multipart recovery contract parts must not be empty." }
        val firstContent = parts.first()
        val replacementContent = replacementMultipartPart(firstContent)
        val secondContent = parts.getOrElse(1) { firstContent }
        val upload = storageAdapter.beginMultipartUpload(
            multipartRequest(listOf(replacementContent, secondContent)),
        )
        var active = true
        try {
            val second = storageAdapter.uploadPart(
                upload,
                2,
                ByteArrayInputStream(secondContent),
                secondContent.size.toLong(),
            )
            storageAdapter.uploadPart(
                upload,
                1,
                ByteArrayInputStream(firstContent),
                firstContent.size.toLong(),
            )
            val replacement = storageAdapter.uploadPart(
                upload,
                1,
                ByteArrayInputStream(replacementContent),
                replacementContent.size.toLong(),
            )

            assertEquals(
                listOf(replacement, second),
                resumableMultipartStorageAdapter.listUploadedParts(upload),
                "Recovery must return the latest acknowledgements once, ordered by part number.",
            )

            storageAdapter.abortMultipartUpload(upload)
            active = false
            assertThrows(RuntimeException::class.java) {
                resumableMultipartStorageAdapter.listUploadedParts(upload)
            }
        } finally {
            if (active) storageAdapter.abortMultipartUpload(upload)
        }
    }
}
