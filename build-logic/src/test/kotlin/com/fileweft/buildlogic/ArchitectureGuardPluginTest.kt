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
            internal data object KotlinOnlySingleton
            """.trimIndent(),
        )

        val result = runner(projectDir, "verifyFileWeftArchitecture").buildAndFail()

        assertTrue(result.output.contains("forbidden prefix: java.sql."))
        assertTrue(result.output.contains("forbidden prefix: org.springframework."))
        assertTrue(result.output.contains("forbidden Kotlin API syntax: data object"))
        assertTrue(result.output.contains("Reusing configuration cache."))
    }

    @Test
    fun `guard rejects servlet references from Java web API sources without imports`() = withTestProject { projectDir ->
        writeProject(projectDir)
        writeFile(
            projectDir,
            "fileweft-web-api/src/main/java/com/fileweft/web/api/ForbiddenServletReference.java",
            """
            package com.fileweft.web.api;

            final class ForbiddenServletReference {
                private javax.servlet.ServletRequest request;
            }
            """.trimIndent(),
        )

        val result = runner(projectDir, "verifyFileWeftArchitecture").buildAndFail()

        assertTrue(result.output.contains("fileweft-web-api/com/fileweft/web/api/ForbiddenServletReference.java:4"))
        assertTrue(result.output.contains("references javax.servlet."))
        assertTrue(result.output.contains("forbidden prefix: javax.servlet."))
    }

    @Test
    fun `guard rejects a runtime project dependency from the pure web API contract`() = withTestProject { projectDir ->
        writeProject(projectDir)
        writeFile(
            projectDir,
            "fileweft-web-api/build.gradle.kts",
            """
            plugins {
                `java-library`
            }

            dependencies {
                api(project(":fileweft-core"))
            }
            """.trimIndent(),
        )

        val result = runner(projectDir, "verifyFileWeftWebApiDependencies").buildAndFail()

        assertTrue(result.output.contains("fileweft-web-api must remain a pure transport contract"))
        assertTrue(result.output.contains("project::fileweft-core"))
    }

    @Test
    fun `guard permits the outer web runtime boundary but rejects framework imports`() = withTestProject { projectDir ->
        writeProject(projectDir)
        writeFile(
            projectDir,
            "fileweft-web-runtime/src/main/kotlin/com/fileweft/web/runtime/AllowedBoundary.kt",
            """
            package com.fileweft.web.runtime

            import com.fileweft.application.document.DocumentQueryService
            import com.fileweft.core.id.Identifier
            import com.fileweft.domain.document.LifecycleState
            import com.fileweft.web.api.ApiPage
            import java.util.Base64

            internal class AllowedBoundary(
                val queries: DocumentQueryService,
                val id: Identifier,
                val state: LifecycleState,
                val page: ApiPage<String>,
                val encoder: Base64.Encoder,
            )
            """.trimIndent(),
        )

        runner(projectDir, "verifyFileWeftArchitecture").build()

        writeFile(
            projectDir,
            "fileweft-web-runtime/src/main/kotlin/com/fileweft/web/runtime/ForbiddenFramework.kt",
            """
            package com.fileweft.web.runtime

            import org.springframework.context.ApplicationContext

            internal class ForbiddenFramework(val context: ApplicationContext)
            """.trimIndent(),
        )

        val result = runner(projectDir, "verifyFileWeftArchitecture").buildAndFail()

        assertTrue(result.output.contains("fileweft-web-runtime/com/fileweft/web/runtime/ForbiddenFramework.kt:3"))
        assertTrue(result.output.contains("forbidden prefix: org.springframework."))
    }

    private fun writeProject(projectDir: File) {
        writeFile(
            projectDir,
            "settings.gradle.kts",
            """
            rootProject.name = "architecture-guard-fixture"
            include(":fileweft-core")
            include(":fileweft-web-api")
            include(":fileweft-web-runtime")
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
        writeFile(
            projectDir,
            "fileweft-web-api/build.gradle.kts",
            """
            plugins {
                base
            }
            """.trimIndent(),
        )
        writeFile(
            projectDir,
            "fileweft-web-runtime/build.gradle.kts",
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
