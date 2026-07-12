package ai.icen.fw.dev.config

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class DevMigrationTopologyTest {
    @Test
    fun `compose assigns migration ownership to API and makes worker validate after API health`() {
        val compose = yaml(workspaceFile(".docker", "docker-compose.dev.yaml"))
        assertEquals("fw-dev", compose.string("name"))

        val services = compose.map("services")
        val platform = services.map("fileweft-dev-platform")
        val api = services.map("fileweft-dev-api")
        val worker = services.map("fileweft-dev-worker")

        assertEquals(
            "jdbc:postgresql://postgres:5432/fileweft?currentSchema=fileweft_dev",
            api.map("environment").string("FILEWEFT_DEV_DB_URL"),
        )
        assertEquals("MIGRATE", api.map("environment").string("FILEWEFT_PERSISTENCE_MIGRATION_MODE"))
        assertEquals("fileweft_dev", api.map("environment").string("FILEWEFT_PERSISTENCE_SCHEMA"))
        assertEquals("true", api.map("environment").string("FILEWEFT_PERSISTENCE_CREATE_SCHEMA"))

        assertEquals(
            "jdbc:postgresql://postgres:5432/fileweft?currentSchema=fileweft_dev",
            worker.map("environment").string("FILEWEFT_DEV_DB_URL"),
        )
        assertEquals("VALIDATE", worker.map("environment").string("FILEWEFT_PERSISTENCE_MIGRATION_MODE"))
        assertEquals("fileweft_dev", worker.map("environment").string("FILEWEFT_PERSISTENCE_SCHEMA"))
        assertEquals("false", worker.map("environment").string("FILEWEFT_PERSISTENCE_CREATE_SCHEMA"))
        assertEquals(
            "service_healthy",
            worker.map("depends_on").map("fileweft-dev-api").string("condition"),
        )

        assertEquals(
            listOf(
                "ai.icen.fw.dev.platform.DevPlatformApplicationKt",
                "--spring.profiles.active=platform",
            ),
            platform.strings("command"),
        )
        assertEquals(
            "jdbc:postgresql://postgres:5432/fileweft?currentSchema=fileweft_dev_platform",
            platform.map("environment").string("FILEWEFT_PLATFORM_DB_URL"),
        )
    }

    @Test
    fun `application profiles disable Boot scanning and keep downstream platform outside FileWeft migration ownership`() {
        val application = yaml(projectFile("src", "main", "resources", "application.yml"))
        val platform = yaml(projectFile("src", "main", "resources", "application-platform.yml"))

        assertEquals(false, application.map("spring").map("flyway")["enabled"])
        assertEquals("migrate", application.map("fileweft").map("persistence").string("migration-mode"))
        assertEquals("fileweft_dev", application.map("fileweft").map("persistence").string("schema"))
        assertEquals(true, application.map("fileweft").map("persistence")["create-schema"])

        val platformPersistence = platform.map("fileweft").map("persistence")
        assertEquals("disabled", platformPersistence.string("migration-mode"))
        assertEquals("", platformPersistence.string("schema"))
        assertEquals(false, platformPersistence["create-schema"])
    }

    private fun yaml(path: Path): Map<String, Any?> = Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
        @Suppress("UNCHECKED_CAST")
        (Yaml().load<Any?>(reader) as? Map<String, Any?>)
            ?: error("Expected a YAML object in $path")
    }

    private fun workspaceFile(vararg segments: String): Path =
        segments.fold(workspaceRoot()) { path, segment -> path.resolve(segment) }

    private fun projectFile(vararg segments: String): Path =
        segments.fold(workspaceRoot().resolve("fileweft-dev")) { path, segment -> path.resolve(segment) }

    private fun workspaceRoot(): Path {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        return generateSequence(workingDirectory) { path -> path.parent }
            .firstOrNull { path ->
                Files.isRegularFile(path.resolve("settings.gradle.kts")) && Files.isDirectory(path.resolve(".docker"))
            }
            ?: error("Could not locate the FileWeft workspace from $workingDirectory")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(name: String): Map<String, Any?> =
        this[name] as? Map<String, Any?> ?: error("Expected YAML object '$name'")

    private fun Map<String, Any?>.string(name: String): String =
        this[name]?.toString() ?: error("Expected YAML scalar '$name'")

    private fun Map<String, Any?>.strings(name: String): List<String> =
        (this[name] as? List<*>)?.mapIndexed { index, value ->
            value as? String ?: error("Expected YAML string at '$name[$index]'")
        } ?: error("Expected YAML list '$name'")
}
