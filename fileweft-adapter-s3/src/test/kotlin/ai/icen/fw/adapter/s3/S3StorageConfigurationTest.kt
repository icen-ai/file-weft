package ai.icen.fw.adapter.s3

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI

class S3StorageConfigurationTest {
    @Test
    fun `accepts a valid path style compatible endpoint`() {
        assertDoesNotThrow {
            S3StorageConfiguration(
                endpoint = URI("http://127.0.0.1:9000"),
                region = "us-east-1",
                accessKey = "rustfsadmin",
                secretKey = "ChangeMe123!",
                bucket = "fileweft-integration",
            )
        }
    }

    @Test
    fun `rejects a non HTTP endpoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            S3StorageConfiguration(
                endpoint = URI("file:///tmp/storage"),
                region = "us-east-1",
                accessKey = "access",
                secretKey = "secret",
                bucket = "fileweft-integration",
            )
        }
    }

    @Test
    fun `rejects an invalid bucket name`() {
        assertThrows(IllegalArgumentException::class.java) {
            S3StorageConfiguration(
                endpoint = URI("https://storage.example.test"),
                region = "us-east-1",
                accessKey = "access",
                secretKey = "secret",
                bucket = "Invalid_Bucket",
            )
        }
    }
}
