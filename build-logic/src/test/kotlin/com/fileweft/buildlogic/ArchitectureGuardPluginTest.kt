package com.fileweft.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureGuardPluginTest {

    @Test
    fun `subproject check executes the architecture guard for approved imports`() = withTestProject { projectDir ->
        writeProject(projectDir)
        writeFile(
            projectDir,
            "fileweft-core/src/main/kotlin/com/fileweft/core/ApprovedImport.kt",
            """
            package com.fileweft.core

            import java.time.Instant

            internal class ApprovedImport(val createdAt: Instant)
            """.trimIndent(),
        )

        val result = runner(projectDir, ":fileweft-core:check").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyFileWeftArchitecture")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":fileweft-core:check")?.outcome)
        assertTrue(result.output.contains("Configuration cache entry stored."))
    }

    @Test
    fun `guard rejects database and unknown framework imports while reusing configuration cache`() = withTestProject { projectDir ->
        writeProject(projectDir)
        writeFile(
            projectDir,
            "fileweft-core/src/main/kotlin/com/fileweft/core/ApprovedImport.kt",
            """
            package com.fileweft.core

            import java.time.Instant

            internal class ApprovedImport(val createdAt: Instant)
            """.trimIndent(),
        )
        runner(projectDir, "verifyFileWeftArchitecture").build()

        writeFile(
            projectDir,
            "fileweft-core/src/main/kotlin/com/fileweft/core/ForbiddenImport.kt",
            """
            package com.fileweft.core

            import java.sql.Connection
            import org.springframework.context.ApplicationContext

            internal typealias ForbiddenImport = Pair<Connection, ApplicationContext>
            """.trimIndent(),
        )

        val result = runner(projectDir, "verifyFileWeftArchitecture").buildAndFail()

        assertTrue(result.output.contains("forbidden prefix: java.sql."))
        assertTrue(result.output.contains("not approved for fileweft-core"))
        assertTrue(result.output.contains("Reusing configuration cache."))
    }

    private fun writeProject(projectDir: File) {
        writeFile(
            projectDir,
            "settings.gradle.kts",
            """
            rootProject.name = "architecture-guard-fixture"
            include(":fileweft-core")
            """.trimIndent(),
        )
        writeFile(
            projectDir,
            "build.gradle.kts",
            """
            plugins {
                id("fileweft.architecture-guard")
            }
            """.trimIndent(),
        )
        writeFile(
            projectDir,
            "fileweft-core/build.gradle.kts",
            """
            plugins {
                base
            }
            """.trimIndent(),
        )
    }

    private fun runner(projectDir: File, task: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments("--configuration-cache", task)

    private fun writeFile(projectDir: File, relativePath: String, content: String) {
        val target = projectDir.resolve(relativePath)
        target.parentFile.mkdirs()
        target.writeText(content + "\n", StandardCharsets.UTF_8)
    }

    private fun withTestProject(action: (File) -> Unit) {
        val projectDir = Files.createTempDirectory("fileweft-architecture-guard-").toFile()
        try {
            action(projectDir)
        } finally {
            assertTrue(projectDir.deleteRecursively(), "Could not remove temporary test project ${projectDir.absolutePath}")
        }
    }
}
