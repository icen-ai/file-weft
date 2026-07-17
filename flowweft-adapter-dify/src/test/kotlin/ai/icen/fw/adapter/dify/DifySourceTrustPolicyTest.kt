package ai.icen.fw.adapter.dify

import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.UnknownHostException

class DifySourceTrustPolicyTest {
    @Test
    fun `accepts only the configured HTTPS origin`() {
        val policy = DifySourceTrustPolicy(listOf(URI("https://files.example.test")))

        assertDoesNotThrow {
            policy.requireTrustedSourceUri(URI("https://files.example.test/path/document.txt?signature=opaque"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.requireTrustedSourceUri(URI("https://other.example.test/path/document.txt"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.requireTrustedSourceUri(URI("http://files.example.test/path/document.txt"))
        }
    }

    @Test
    fun `rejects user info fragments and normalized dot segments`() {
        val policy = DifySourceTrustPolicy(listOf(URI("https://files.example.test")))

        assertThrows(IllegalArgumentException::class.java) {
            policy.requireTrustedSourceUri(URI("https://user@files.example.test/document.txt"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.requireTrustedSourceUri(URI("https://files.example.test/document.txt#fragment"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.requireTrustedSourceUri(URI("https://files.example.test/a/../document.txt"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.requireTrustedSourceUri(URI("https://files.example.test/a/%2e%2e/document.txt"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.requireTrustedSourceUri(URI("https://files.example.test/a%2fdocument.txt"))
        }
    }

    @Test
    fun `rejects private resolution unless administrator explicitly enables it`() {
        val defaultPolicy = DifySourceTrustPolicy(listOf(URI("https://127.0.0.1")))
        val privateProfile = DifySourceTrustPolicy(
            listOf(URI("https://127.0.0.1")),
            allowPrivateAddresses = true,
        )

        assertThrows(UnknownHostException::class.java) { defaultPolicy.resolveTrusted("127.0.0.1") }
        assertThrows(UnknownHostException::class.java) { defaultPolicy.resolveTrusted("2002:a00:1::") }
        assertDoesNotThrow { privateProfile.resolveTrusted("127.0.0.1") }
        assertThrows(UnknownHostException::class.java) { DifyValidatingDns(false).lookup("127.0.0.1") }
        assertDoesNotThrow { DifyValidatingDns(true).lookup("127.0.0.1") }
    }

    @Test
    fun `rejects unsafe administrator origins at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            DifySourceTrustPolicy(listOf(URI("http://files.example.test")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DifySourceTrustPolicy(listOf(URI("https://files.example.test/path")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DifySourceTrustPolicy(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DifyKnowledgeBaseProfile(
                "dify-main",
                Identifier("tenant-a"),
                URI("https://dify.example.test/a/%2e%2e/v1"),
                TEST_DATASET_ID,
                DifySourceTrustPolicy(listOf(URI("https://files.example.test"))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DifyDocumentIndexingOptions(documentLanguage = "bad\uD800language")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StaticDifyApiKeyProvider(charArrayOf('b', 'a', 'd', '\uDC00'))
        }
    }

    @Test
    fun `target binding changes when the configured API authority changes`() {
        val trust = DifySourceTrustPolicy(listOf(URI("https://files.example.test")))
        val first = DifyKnowledgeBaseProfile(
            "dify-main",
            Identifier("tenant-a"),
            URI("https://dify-a.example.test/v1"),
            TEST_DATASET_ID,
            trust,
        )
        val second = DifyKnowledgeBaseProfile(
            "dify-main",
            Identifier("tenant-a"),
            URI("https://dify-b.example.test/v1"),
            TEST_DATASET_ID,
            trust,
        )

        assertNotEquals(first.targetBindingDigest, second.targetBindingDigest)
    }
}
