package ai.icen.fw.migration.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowWeftMigrationDistributionContractTest {
    @Test
    fun `runtime image is digest pinned non-root and contains only the install distribution`() {
        val dockerfile = readModuleFile("Dockerfile")

        assertTrue(
            Regex("FROM eclipse-temurin:21-jre-jammy@sha256:[0-9a-f]{64}").containsMatchIn(dockerfile),
        )
        assertTrue(dockerfile.contains("COPY --chown=65532:65532 flowweft-migration-cli/build/install/"))
        assertTrue(dockerfile.contains("USER 65532:65532"))
        assertTrue(dockerfile.contains("HEALTHCHECK NONE"))
        assertTrue(dockerfile.contains("FlowWeftMigrationCliKt"))
        assertFalse(dockerfile.contains("FLOWWEFT_MIGRATION_JDBC_PASSWORD="))
        assertFalse(dockerfile.contains("gradle", ignoreCase = true) && dockerfile.contains("COPY ."))
    }

    @Test
    fun `example Job is single-shot least-privilege and file-secret only`() {
        val job = readModuleFile("kubernetes/job.example.yaml").replace("\r\n", "\n")

        assertTrue(job.contains("kind: Job"))
        assertTrue(job.contains("backoffLimit: 0"))
        assertTrue(job.contains("completions: 1"))
        assertTrue(job.contains("parallelism: 1"))
        assertTrue(job.contains("restartPolicy: Never"))
        assertTrue(job.contains("automountServiceAccountToken: false"))
        assertTrue(job.contains("runAsNonRoot: true"))
        assertTrue(job.contains("readOnlyRootFilesystem: true"))
        assertTrue(job.contains("allowPrivilegeEscalation: false"))
        assertTrue(
            Regex("(?m)^\\s*capabilities:\\s*\\r?\\n\\s*drop:\\s*\\r?\\n\\s*- ALL\\s*$")
                .containsMatchIn(job),
        )
        assertTrue(job.contains("name: FLOWWEFT_MIGRATION_JDBC_PASSWORD_FILE"))
        assertTrue(job.contains("value: /var/run/secrets/flowweft-db/password"))
        assertTrue(job.contains("name: FLOWWEFT_MIGRATION_LINES\n              value: all"))
        assertTrue(job.contains("name: FLOWWEFT_MIGRATION_CREATE_SCHEMA\n              value: \"false\""))
        assertTrue(job.contains("name: tmp\n              mountPath: /tmp"))
        assertTrue(job.contains("emptyDir:\n            sizeLimit: 64Mi"))
        assertTrue(Regex("image: [^\\s]+@sha256:[0-9a-f]{64}").containsMatchIn(job))
        assertFalse(job.contains("name: FLOWWEFT_MIGRATION_JDBC_PASSWORD\n"))
        assertFalse(Regex("(?m)^\\s*(data|stringData):").containsMatchIn(job))
    }

    private fun readModuleFile(relative: String): String {
        val path = moduleFile(relative)
        return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }

    private fun moduleFile(relative: String): Path {
        val candidates = listOf(
            Paths.get("flowweft-migration-cli").resolve(relative),
            Paths.get(relative),
        )
        return candidates.firstOrNull(Files::isRegularFile)
            ?: error("Cannot locate flowweft-migration-cli/$relative from ${Paths.get("").toAbsolutePath()}")
    }
}
