package ai.icen.fw.adapter.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.icen.fw.spi.storage.MultipartUpload;
import ai.icen.fw.spi.storage.PresignedUploadFinalizeRequest;
import ai.icen.fw.spi.storage.PresignedUploadCleanupRequest;
import ai.icen.fw.spi.storage.PresignedUploadGrantRequest;
import ai.icen.fw.spi.storage.PresignedUploadReissueRequest;
import ai.icen.fw.spi.storage.StorageObjectLocation;
import ai.icen.fw.spi.storage.StorageRangeRequest;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OssStorageAdapterJavaCompatibilityTest {
    @Test
    void exposesJavaFriendlyConfigurationCapabilitiesAndDoctor() throws Exception {
        OssCredentialsProvider rotatingProvider = () ->
            new OssCredentials("test-access", "test-secret", "test-token", System.currentTimeMillis() + 3_600_000L);
        OssStorageConfiguration configuration = new OssStorageConfiguration(
            URI.create("https://oss-cn-hangzhou.aliyuncs.com"),
            "cn-hangzhou",
            "flowweft-java-test",
            rotatingProvider
        );
        OssStorageClientPolicy policy = new OssStorageClientPolicy(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            2
        );

        try (OssStorageAdapter defaultPolicy = new OssStorageAdapter(configuration);
             OssStorageAdapter explicitPolicy = new OssStorageAdapter(configuration, policy)) {
            assertNotNull(defaultPolicy);
            assertNotNull(explicitPolicy);
            assertNotNull(new OssStorageDoctorChecker(explicitPolicy));
            assertEquals(
                OssStorageFailureCategory.INVALID_REQUEST,
                OssStorageFailureCategory.valueOf("INVALID_REQUEST")
            );
            assertNotNull(
                OssStorageAdapter.class.getMethod("metadata", StorageObjectLocation.class)
            );
            assertNotNull(
                OssStorageAdapter.class.getMethod("downloadRange", StorageRangeRequest.class)
            );
            assertNotNull(
                OssStorageAdapter.class.getMethod("listUploadedParts", MultipartUpload.class)
            );
            assertNotNull(
                OssStorageAdapter.class.getMethod("createUploadGrant", PresignedUploadGrantRequest.class)
            );
            assertNotNull(
                OssStorageAdapter.class.getMethod("finalizeUpload", PresignedUploadFinalizeRequest.class)
            );
            assertNotNull(
                OssStorageAdapter.class.getMethod("reissueUploadGrant", PresignedUploadReissueRequest.class)
            );
            assertNotNull(
                OssStorageAdapter.class.getMethod("cleanupUpload", PresignedUploadCleanupRequest.class)
            );
            int supportedConstructorCount = 0;
            for (java.lang.reflect.Constructor<?> constructor : OssStorageAdapter.class.getConstructors()) {
                if (constructor.isSynthetic()) {
                    continue;
                }
                supportedConstructorCount++;
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    assertTrue(
                        parameterType == OssStorageConfiguration.class ||
                            parameterType == OssStorageClientPolicy.class,
                        "Public Java constructors must not expose an internal SDK facade or test clock."
                    );
                }
            }
            assertEquals(2, supportedConstructorCount);
            assertNotNull(rotatingProvider.resolve().getExpiresAt());
            assertEquals(Duration.ofSeconds(30), policy.getCredentialExpirySafetyWindow());
        }
    }
}
