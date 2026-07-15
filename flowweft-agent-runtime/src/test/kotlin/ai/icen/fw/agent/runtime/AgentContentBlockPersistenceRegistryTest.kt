package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentBinaryContentBlock
import ai.icen.fw.agent.api.AgentCitation
import ai.icen.fw.agent.api.AgentCitationContentBlock
import ai.icen.fw.agent.api.AgentContentBlock
import ai.icen.fw.agent.api.AgentContentOrigin
import ai.icen.fw.agent.api.AgentSizedContentBlock
import ai.icen.fw.agent.api.AgentTextContentBlock
import ai.icen.fw.agent.api.AgentToolCallContentBlock
import ai.icen.fw.agent.api.AgentToolResultContentBlock
import ai.icen.fw.agent.api.AgentToolResultStatus
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.core.id.Identifier
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class AgentContentBlockPersistenceRegistryTest {
    private val registry = AgentContentBlockPersistenceRegistry(listOf(NoteCodec()))

    @Test
    fun `round trips every built-in content kind with exact security binding`() {
        val arguments = "{\"documentId\":\"doc-1\"}".toByteArray(StandardCharsets.UTF_8)
        val binary = "binary-evidence".toByteArray(StandardCharsets.UTF_8)
        val blocks = listOf(
            AgentTextContentBlock(AgentContentOrigin.USER, "请审阅这份合同"),
            AgentBinaryContentBlock(
                AgentContentOrigin.USER,
                "application/octet-stream",
                binary,
                runtimeSha256(binary),
            ),
            AgentToolCallContentBlock(
                "call-1",
                ToolId("document.review"),
                ZERO_DIGEST,
                arguments,
                runtimeSha256(arguments),
            ),
            AgentCitationContentBlock(
                AgentCitation(
                    Identifier("citation-1"),
                    Identifier("tenant-1"),
                    Identifier("document-1"),
                    Identifier("version-1"),
                    Identifier("evidence-1"),
                    ZERO_DIGEST,
                    10,
                    20,
                    2,
                ),
            ),
            AgentToolResultContentBlock(
                "call-1",
                ToolId("document.review"),
                AgentToolResultStatus.SUCCEEDED,
                listOf(AgentTextContentBlock(AgentContentOrigin.TOOL, "accepted")),
            ),
        )

        blocks.forEach { block ->
            val encoded = registry.encode(block)
            val decoded = registry.decode(encoded)

            assertNotSame(block, decoded)
            assertEquals(block.kind(), decoded.kind())
            assertEquals(block.origin(), decoded.origin())
            assertEquals(block.bindingDigest(), decoded.bindingDigest())
            assertEquals(
                (block as AgentSizedContentBlock).canonicalPayloadSizeBytes(),
                (decoded as AgentSizedContentBlock).canonicalPayloadSizeBytes(),
            )
            assertContentEquals(encoded.payload, registry.encode(decoded).payload)
        }
    }

    @Test
    fun `round trips an explicitly registered extension without exposing mutable bytes`() {
        val block = NoteBlock(AgentContentOrigin.MEMORY, "retained fact")
        val encoded = registry.encode(block)
        val leaked = encoded.payload
        leaked.fill(0)

        val decoded = registry.decode(encoded) as NoteBlock
        assertEquals("retained fact", decoded.value)
        assertEquals(block.bindingDigest(), decoded.bindingDigest())
    }

    @Test
    fun `reads prior extension versions while writing only the greatest registered version`() {
        val block = NoteBlock(AgentContentOrigin.MEMORY, "versioned fact")
        val prior = AgentContentBlockPersistenceRegistry(listOf(NoteCodec(1))).encode(block)
        val upgraded = AgentContentBlockPersistenceRegistry(listOf(NoteCodec(1), NoteCodec(2)))

        assertEquals(1, prior.codecVersion)
        assertEquals("versioned fact", (upgraded.decode(prior) as NoteBlock).value)
        assertEquals(2, upgraded.encode(block).codecVersion)
    }

    @Test
    fun `rejects missing duplicate reserved and lossy extension codecs`() {
        val block = NoteBlock(AgentContentOrigin.MEMORY, "fact")
        assertFailsWith<IllegalArgumentException> {
            AgentContentBlockPersistenceRegistry().encode(block)
        }
        assertFailsWith<IllegalArgumentException> {
            AgentContentBlockPersistenceRegistry(listOf(NoteCodec(), NoteCodec()))
        }
        assertFailsWith<IllegalArgumentException> {
            AgentContentBlockPersistenceRegistry(listOf(ReservedTextCodec()))
        }
        assertFailsWith<IllegalArgumentException> {
            AgentContentBlockPersistenceRegistry(listOf(LossyNoteCodec())).encode(block)
        }
    }

    @Test
    fun `redacts extension codec exceptions that may contain persisted content`() {
        val block = NoteBlock(AgentContentOrigin.MEMORY, "tenant secret")
        val encodingFailure = assertFailsWith<IllegalArgumentException> {
            AgentContentBlockPersistenceRegistry(listOf(ThrowingEncodeNoteCodec())).encode(block)
        }
        assertEquals("Agent extension content codec failed while encoding.", encodingFailure.message)

        val encoded = registry.encode(block)
        val decodingFailure = assertFailsWith<IllegalArgumentException> {
            AgentContentBlockPersistenceRegistry(listOf(ThrowingDecodeNoteCodec())).decode(encoded)
        }
        assertEquals("Agent extension content codec failed while decoding.", decodingFailure.message)
    }

    @Test
    fun `fails closed for digest drift unknown versions and trailing payload`() {
        val source = registry.encode(AgentTextContentBlock(AgentContentOrigin.USER, "bounded"))
        assertFailsWith<IllegalArgumentException> {
            AgentEncodedContentBlock.restore(
                source.kind,
                source.origin,
                source.codecVersion,
                source.bindingDigest,
                source.payload + 0,
                source.canonicalPayloadSizeBytes,
                source.payloadDigest,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            registry.decode(
                AgentEncodedContentBlock(
                    source.kind,
                    source.origin,
                    source.codecVersion,
                    ZERO_DIGEST,
                    source.payload,
                    source.canonicalPayloadSizeBytes,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            registry.decode(
                AgentEncodedContentBlock(
                    source.kind,
                    source.origin,
                    2,
                    source.bindingDigest,
                    source.payload,
                    source.canonicalPayloadSizeBytes,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            registry.decode(
                AgentEncodedContentBlock(
                    source.kind,
                    source.origin,
                    source.codecVersion,
                    source.bindingDigest,
                    source.payload + 0,
                    source.canonicalPayloadSizeBytes,
                ),
            )
        }
    }

    @Test
    fun `rejects malformed utf8 and nested execution control before recursive decoding`() {
        val source = registry.encode(AgentTextContentBlock(AgentContentOrigin.USER, "bounded"))
        val malformed = source.payload
        malformed[4] = 0xc3.toByte()
        malformed[5] = 0x28
        assertFailsWith<IllegalArgumentException> {
            registry.decode(
                AgentEncodedContentBlock(
                    source.kind,
                    source.origin,
                    source.codecVersion,
                    source.bindingDigest,
                    malformed,
                    source.canonicalPayloadSizeBytes,
                ),
            )
        }

        val nestedControl = AgentEncodedContentBlock(
            AgentToolResultContentBlock.KIND,
            AgentContentOrigin.TOOL,
            1,
            ZERO_DIGEST,
            ByteArray(0),
            0,
        )
        val outerPayload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeText("call-1")
                output.writeText("document.review")
                output.writeText(AgentToolResultStatus.SUCCEEDED.name)
                output.writeBoolean(false)
                output.writeInt(1)
                output.writeEncoded(nestedControl)
            }
        }.toByteArray()
        assertFailsWith<IllegalArgumentException> {
            registry.decode(
                AgentEncodedContentBlock(
                    AgentToolResultContentBlock.KIND,
                    AgentContentOrigin.TOOL,
                    1,
                    ZERO_DIGEST,
                    outerPayload,
                ),
            )
        }
    }

    @Test
    fun `bounded malformed corpus never becomes content or escapes through an Error`() {
        val random = Random(0xF10F_EE7L)
        for (size in 0..512) {
            val payload = ByteArray(size).also(random::nextBytes)
            assertFailsWith<Exception>("Malformed payload size $size unexpectedly decoded.") {
                registry.decode(
                    AgentEncodedContentBlock(
                        AgentTextContentBlock.KIND,
                        AgentContentOrigin.USER,
                        1,
                        ZERO_DIGEST,
                        payload,
                    ),
                )
            }
        }
    }

    private fun DataOutputStream.writeText(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeEncoded(value: AgentEncodedContentBlock) {
        writeText(value.kind)
        writeText(value.origin.name)
        writeInt(value.codecVersion)
        writeText(value.bindingDigest)
        writeText(value.payloadDigest)
        writeBoolean(value.canonicalPayloadSizeBytes != null)
        value.canonicalPayloadSizeBytes?.let { size -> writeLong(size) }
        val payload = value.payload
        writeInt(payload.size)
        write(payload)
    }

    private class NoteBlock(
        private val origin: AgentContentOrigin,
        val value: String,
    ) : AgentSizedContentBlock {
        override fun kind(): String = KIND

        override fun origin(): AgentContentOrigin = origin

        override fun bindingDigest(): String = runtimeSha256(
            "$KIND\u0000${origin.name}\u0000$value".toByteArray(StandardCharsets.UTF_8),
        )

        override fun canonicalPayloadSizeBytes(): Long = value.toByteArray(StandardCharsets.UTF_8).size.toLong()

        companion object {
            const val KIND = "example-note"
        }
    }

    private open class NoteCodec(
        private val version: Int = 1,
    ) : AgentContentBlockPersistenceCodec {
        override fun kind(): String = NoteBlock.KIND

        override fun codecVersion(): Int = version

        override fun encode(block: AgentContentBlock): ByteArray =
            (block as NoteBlock).value.toByteArray(StandardCharsets.UTF_8)

        override fun decode(origin: AgentContentOrigin, payload: ByteArray): AgentContentBlock =
            NoteBlock(origin, String(payload, StandardCharsets.UTF_8))
    }

    private class LossyNoteCodec : NoteCodec() {
        override fun decode(origin: AgentContentOrigin, payload: ByteArray): AgentContentBlock =
            NoteBlock(origin, "changed")
    }

    private class ThrowingEncodeNoteCodec : NoteCodec() {
        override fun encode(block: AgentContentBlock): ByteArray =
            throw IllegalArgumentException((block as NoteBlock).value)
    }

    private class ThrowingDecodeNoteCodec : NoteCodec() {
        override fun decode(origin: AgentContentOrigin, payload: ByteArray): AgentContentBlock =
            throw IllegalArgumentException(String(payload, StandardCharsets.UTF_8))
    }

    private class ReservedTextCodec : AgentContentBlockPersistenceCodec {
        override fun kind(): String = AgentTextContentBlock.KIND

        override fun codecVersion(): Int = 1

        override fun encode(block: AgentContentBlock): ByteArray = ByteArray(0)

        override fun decode(origin: AgentContentOrigin, payload: ByteArray): AgentContentBlock =
            AgentTextContentBlock(origin, "invalid")
    }

    private companion object {
        const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
