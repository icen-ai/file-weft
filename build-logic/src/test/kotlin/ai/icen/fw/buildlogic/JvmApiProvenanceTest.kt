package ai.icen.fw.buildlogic

import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmApiProvenanceTest {
    @Test
    fun `raw bytes are bound before parsing`() {
        val file = Files.createTempFile("flowweft-api-provenance-", ".api")
        try {
            file.writeBytes("# trusted\n".toByteArray())
            val expected = JvmApiProvenance.sha256(file.toFile())
            assertEquals(expected, JvmApiProvenance.requireDigest(file.toFile(), expected, "fixture API"))

            file.writeBytes("# tampered\n".toByteArray())
            val failure = assertFailsWith<IllegalArgumentException> {
                JvmApiProvenance.requireDigest(file.toFile(), expected, "fixture API")
            }
            assertTrue(failure.message.orEmpty().contains("bytes differ from provenance"), failure.message)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `invalid expected digest fails closed`() {
        val file = Files.createTempFile("flowweft-api-provenance-", ".api")
        try {
            file.writeBytes(byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> {
                JvmApiProvenance.requireDigest(file.toFile(), "PENDING", "fixture API")
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
