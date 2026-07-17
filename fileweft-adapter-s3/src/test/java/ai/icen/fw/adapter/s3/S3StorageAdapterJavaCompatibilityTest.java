package ai.icen.fw.adapter.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class S3StorageAdapterJavaCompatibilityTest {
    @Test
    void retainsTheReleasedConstructorAndExposesJavaFriendlyAdditivePolicy() throws Exception {
        S3StorageConfiguration configuration = new S3StorageConfiguration(
            URI.create("http://127.0.0.1:9000"),
            "us-east-1",
            "access",
            "secret",
            "fileweft-integration",
            true,
            S3StorageAdapter.STORAGE_TYPE
        );

        try (S3StorageAdapter releasedConstructor = new S3StorageAdapter(configuration);
             S3StorageAdapter configuredConstructor = new S3StorageAdapter(
                 configuration,
                 new S3StorageClientPolicy(
                     Duration.ofSeconds(1),
                     Duration.ofSeconds(2),
                     Duration.ofSeconds(3),
                     Duration.ofSeconds(4),
                     2
                 )
             )) {
            assertNotNull(releasedConstructor);
            assertNotNull(configuredConstructor);
            assertNotNull(new S3StorageDoctorChecker(configuredConstructor));
            assertEquals(
                S3StorageFailureCategory.INTEGRITY,
                S3StorageFailureCategory.valueOf("INTEGRITY")
            );
            assertEquals(
                S3StorageMissingResource.MULTIPART_UPLOAD,
                S3StorageMissingResource.valueOf("MULTIPART_UPLOAD")
            );
            assertNotNull(
                S3StorageAdapter.class.getMethod(
                    "downloadRange",
                    ai.icen.fw.spi.storage.StorageObjectLocation.class,
                    long.class,
                    long.class
                )
            );
            for (java.lang.reflect.Constructor<?> constructor : S3StorageAdapter.class.getConstructors()) {
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    assertFalse(
                        parameterType.getName().startsWith("software.amazon.awssdk"),
                        "The public adapter constructor must not expose an AWS SDK type."
                    );
                }
            }
        }
    }
}
