package ai.icen.fw.adapter.oss

import com.aliyun.oss.common.auth.CredentialsProvider
import com.aliyun.oss.common.auth.DefaultCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class OssStorageConfigurationTest {
    @Test
    fun `accepts only an explicit secret-free HTTPS endpoint`() {
        val configuration = configuration()

        val rendered = configuration.toString()

        assertTrue(rendered.contains("endpointScheme=https"))
        assertFalse(rendered.contains("oss-cn-hangzhou.aliyuncs.com"))
        assertFalse(rendered.contains(TEST_BUCKET))
        assertFalse(rendered.contains(TEST_ACCESS_KEY))
        assertFalse(rendered.contains(TEST_ACCESS_SECRET))
    }

    @Test
    fun `rejects unsafe endpoint forms and conflicting addressing modes`() {
        listOf(
            "http://oss-cn-hangzhou.aliyuncs.com",
            "https://user:password@oss-cn-hangzhou.aliyuncs.com",
            "https://oss-cn-hangzhou.aliyuncs.com/bucket",
            "https://oss-cn-hangzhou.aliyuncs.com?token=secret",
            "https://oss-cn-hangzhou.aliyuncs.com/#secret",
            "https://oss-cn-hangzhou.aliyuncs.com:0",
            "https://oss-cn-hangzhou.aliyuncs.com:65536",
        ).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                configuration(URI.create(endpoint))
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            OssStorageConfiguration(
                endpoint = TEST_ENDPOINT,
                region = TEST_REGION,
                bucket = TEST_BUCKET,
                credentialsProvider = credentials(),
                usePathStyle = true,
                useCName = true,
            )
        }
    }

    @Test
    fun `rejects blank or unsafe credential material and never renders secrets`() {
        assertThrows(IllegalArgumentException::class.java) {
            StaticOssCredentialsProvider(" ", TEST_ACCESS_SECRET)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StaticOssCredentialsProvider(TEST_ACCESS_KEY, "secret\nheader")
        }

        val provider = credentials()
        val resolved = provider.resolve()
        assertEquals("OssCredentials(redacted)", resolved.toString())
        assertEquals("StaticOssCredentialsProvider(redacted)", provider.toString())
        assertFalse(resolved.toString().contains(TEST_ACCESS_SECRET))
    }

    @Test
    fun `refreshes a rotating host credential provider at every SDK signing lookup`() {
        val sequence = AtomicInteger()
        val rotating = object : OssCredentialsProvider {
            override fun resolve(): OssCredentials {
                val number = sequence.incrementAndGet()
                return OssCredentials("access-$number", "secret-$number", "token-$number", Long.MAX_VALUE)
            }
        }
        val sdkProvider: CredentialsProvider = FlowWeftCredentialsProvider(rotating)

        val first = sdkProvider.credentials
        sdkProvider.setCredentials(DefaultCredentials("override", "override-secret", "override-token"))
        val second = sdkProvider.credentials

        assertEquals("access-1", first.accessKeyId)
        assertEquals("token-1", first.securityToken)
        assertEquals("access-2", second.accessKeyId)
        assertEquals("token-2", second.securityToken)
        assertNotEquals(first.secretAccessKey, second.secretAccessKey)
    }

    @Test
    fun `rejects temporary credentials that cannot cover a complete SDK request`() {
        val now = 1_000_000L
        val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

        val missingExpiry = FlowWeftCredentialsProvider(
            StaticOssCredentialsProvider("access", "secret", "token"),
            minimumValidityMillis = 15_000,
            clock = clock,
        )
        assertThrows(com.aliyun.oss.common.auth.InvalidCredentialsException::class.java) {
            missingExpiry.credentials
        }

        val expiring = FlowWeftCredentialsProvider(
            StaticOssCredentialsProvider("access", "secret", "token", now + 15_000),
            minimumValidityMillis = 15_000,
            clock = clock,
        )
        assertThrows(com.aliyun.oss.common.auth.InvalidCredentialsException::class.java) {
            expiring.credentials
        }

        val valid = FlowWeftCredentialsProvider(
            StaticOssCredentialsProvider("access", "secret", "token", now + 15_001),
            minimumValidityMillis = 15_000,
            clock = clock,
        )
        assertEquals("access", valid.credentials.accessKeyId)
    }

    @Test
    fun `enforces finite millisecond timeout and retry bounds`() {
        val policy = OssStorageClientPolicy(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            2,
        )
        assertEquals(2, policy.maxAttempts)

        assertThrows(IllegalArgumentException::class.java) {
            OssStorageClientPolicy(connectionTimeout = Duration.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OssStorageClientPolicy(maxAttempts = 11)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OssStorageClientPolicy(requestTimeout = Duration.ofDays(2))
        }
    }

    private fun configuration(endpoint: URI = TEST_ENDPOINT): OssStorageConfiguration =
        OssStorageConfiguration(endpoint, TEST_REGION, TEST_BUCKET, credentials())

    private fun credentials(): StaticOssCredentialsProvider =
        StaticOssCredentialsProvider(TEST_ACCESS_KEY, TEST_ACCESS_SECRET, "security-token")

    private companion object {
        val TEST_ENDPOINT: URI = URI.create("https://oss-cn-hangzhou.aliyuncs.com")
        const val TEST_REGION = "cn-hangzhou"
        const val TEST_BUCKET = "flowweft-oss-test"
        const val TEST_ACCESS_KEY = "test-access-key"
        const val TEST_ACCESS_SECRET = "test-access-secret"
    }
}
