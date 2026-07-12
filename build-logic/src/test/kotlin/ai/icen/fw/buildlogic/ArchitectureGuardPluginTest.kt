package ai.icen.fw.buildlogic

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
            "fileweft-core/src/main/kotlin/ai/icen/fw/core/ApprovedImport.kt",
            """
            package ai.icen.fw.core

            import java.time.Instant

            internal class ApprovedImport(val createdAt: Instant)
            """.trimIndent(),
        )
        writeFile(
            projectDir,
            "fileweft-adapter/src/main/resources/META-INF/services/ai.icen.fw.spi.FileConnector",
            "ai.icen.fw.adapter.CurrentConnector",
        )

        val result = runner(projectDir, ":fileweft-core:check").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyFileWeftArchitecture")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":fileweft-core:check")?.outcome)
        assertTrue(result.output.contains("Configuration cache entry stored."))
    }

    @Test
    fun `guard rejects the retired namespace in any module source path and runtime metadata`() =
        withTestProject { projectDir ->
            writeProject(projectDir)
            val retiredDotted = listOf("com", "fileweft").joinToString(".")
            val retiredPath = listOf("com", "fileweft").joinToString("/")
            writeFile(
                projectDir,
                "fileweft-adapter/src/main/kotlin/$retiredPath/adapter/LegacyAdapter.kt",
                "package $retiredDotted.adapter",
            )
            writeFile(
                projectDir,
                "fileweft-adapter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                "$retiredDotted.adapter.LegacyAutoConfiguration",
            )

            val result = runner(projectDir, "verifyFileWeftArchitecture").buildAndFail()

            assertTrue(result.output.contains("LegacyAdapter.kt uses retired namespace path $retiredPath"))
            assertTrue(result.output.contains("LegacyAdapter.kt:1 references retired namespace $retiredDotted"))
            assertTrue(result.output.contains("AutoConfiguration.imports:1 references retired namespace $retiredDotted"))
        }

    @Test
    fun `guard rejects database and unknown framework imports while reusing configuration cache`() = withTestProject { projectDir ->
        writeProject(projectDir)
        writeFile(
            projectDir,
            "fileweft-core/src/main/kotlin/ai/icen/fw/core/ApprovedImport.kt",
            """
            package ai.icen.fw.core

            import java.time.Instant

            internal class ApprovedImport(val createdAt: Instant)
            """.trimIndent(),
        )
        runner(projectDir, "verifyFileWeftArchitecture").build()

        writeFile(
            projectDir,
            "fileweft-core/src/main/kotlin/ai/icen/fw/core/ForbiddenImport.kt",
            """
            package ai.icen.fw.core

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
            "fileweft-web-api/src/main/java/ai/icen/fw/web/api/ForbiddenServletReference.java",
            """
            package ai.icen.fw.web.api;

            final class ForbiddenServletReference {
                private javax.servlet.ServletRequest request;
            }
            """.trimIndent(),
        )

        val result = runner(projectDir, "verifyFileWeftArchitecture").buildAndFail()

        assertTrue(result.output.contains("fileweft-web-api/ai/icen/fw/web/api/ForbiddenServletReference.java:4"))
        assertTrue(result.output.contains("references javax.servlet."))
        assertTrue(result.output.contains("forbidden prefix: javax.servlet."))
    }

    @Test
    fun `guard keeps Spring forbidden in the pure web API contract`() = withTestProject { projectDir ->
        writeProject(projectDir)
        writeFile(
            projectDir,
            "fileweft-web-api/src/main/kotlin/ai/icen/fw/web/api/ForbiddenSpring.kt",
            """
            package ai.icen.fw.web.api

            import org.springframework.http.HttpStatus

            internal class ForbiddenSpring(val status: HttpStatus)
            """.trimIndent(),
        )

        val result = runner(projectDir, "verifyFileWeftArchitecture").buildAndFail()

        assertTrue(result.output.contains("fileweft-web-api/ai/icen/fw/web/api/ForbiddenSpring.kt:3"))
        assertTrue(result.output.contains("forbidden prefix: org.springframework."))
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
            "fileweft-web-runtime/src/main/kotlin/ai/icen/fw/web/runtime/AllowedBoundary.kt",
            """
            package ai.icen.fw.web.runtime

            import ai.icen.fw.application.document.DocumentQueryService
            import ai.icen.fw.core.id.Identifier
            import ai.icen.fw.domain.document.LifecycleState
            import ai.icen.fw.web.api.ApiPage
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
            "fileweft-web-runtime/src/main/kotlin/ai/icen/fw/web/runtime/ForbiddenFramework.kt",
            """
            package ai.icen.fw.web.runtime

            import org.springframework.context.ApplicationContext

            internal class ForbiddenFramework(val context: ApplicationContext)
            """.trimIndent(),
        )

        val result = runner(projectDir, "verifyFileWeftArchitecture").buildAndFail()

        assertTrue(result.output.contains("fileweft-web-runtime/ai/icen/fw/web/runtime/ForbiddenFramework.kt:3"))
        assertTrue(result.output.contains("forbidden prefix: org.springframework."))
    }

    @Test
    fun `guard permits Spring imports only in outer MVC starter modules`() = withTestProject { projectDir ->
        writeProject(projectDir)
        writeFile(
            projectDir,
            "fileweft-web-spring-boot2-starter/src/main/kotlin/ai/icen/fw/web/spring/boot2/Boot2Controller.kt",
            """
            package ai.icen.fw.web.spring.boot2

            import org.springframework.boot.autoconfigure.AutoConfiguration
            import org.springframework.web.bind.annotation.GetMapping
            import org.springframework.web.bind.annotation.RestController

            @RestController
            @AutoConfiguration(afterName = ["ai.icen.fw.starter.boot2.FileWeftAutoConfiguration"])
            internal class Boot2Controller {
                @GetMapping("/fileweft/v1/boot2")
                fun endpoint(): String = "ok"
            }
            """.trimIndent(),
        )
        writeFile(
            projectDir,
            "fileweft-web-spring-boot3-starter/src/main/kotlin/ai/icen/fw/web/spring/boot3/Boot3Controller.kt",
            """
            package ai.icen.fw.web.spring.boot3

            import org.springframework.boot.autoconfigure.AutoConfiguration
            import org.springframework.web.bind.annotation.GetMapping
            import org.springframework.web.bind.annotation.RestController

            @RestController
            @AutoConfiguration(afterName = ["ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"])
            internal class Boot3Controller {
                @GetMapping("/fileweft/v1/boot3")
                fun endpoint(): String = "ok"
            }
            """.trimIndent(),
        )

        val result = runner(projectDir, "verifyFileWeftArchitecture").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyFileWeftArchitecture")?.outcome)
    }

    @Test
    fun `guard rejects JDBC and servlet imports from outer MVC starter modules`() = withTestProject { projectDir ->
        writeProject(projectDir)
        writeFile(
            projectDir,
            "fileweft-web-spring-boot2-starter/src/main/kotlin/ai/icen/fw/web/spring/boot2/ForbiddenJdbc.kt",
            """
            package ai.icen.fw.web.spring.boot2

            import java.sql.Connection

            internal class ForbiddenJdbc(val connection: Connection)
            """.trimIndent(),
        )
        writeFile(
            projectDir,
            "fileweft-web-spring-boot3-starter/src/main/java/ai/icen/fw/web/spring/boot3/ForbiddenServlet.java",
            """
            package ai.icen.fw.web.spring.boot3;

            import jakarta.servlet.http.HttpServletRequest;

            final class ForbiddenServlet {
                private HttpServletRequest request;
            }
            """.trimIndent(),
        )
        writeFile(
            projectDir,
            "fileweft-web-spring-boot2-starter/src/main/kotlin/ai/icen/fw/web/spring/boot2/WrongBootGeneration.kt",
            """
            package ai.icen.fw.web.spring.boot2

            import org.springframework.boot.autoconfigure.AutoConfiguration

            @AutoConfiguration(afterName = ["ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"])
            internal class WrongBootGeneration
            """.trimIndent(),
        )
        writeFile(
            projectDir,
            "fileweft-web-spring-boot2-starter/src/main/kotlin/ai/icen/fw/web/spring/boot2/UnscopedStarterReference.kt",
            """
            package ai.icen.fw.web.spring.boot2

            internal val unscopedStarterReference = "ai.icen.fw.starter.boot2.FileWeftAutoConfiguration"
            """.trimIndent(),
        )

        val result = runner(projectDir, "verifyFileWeftArchitecture").buildAndFail()

        assertTrue(result.output.contains("fileweft-web-spring-boot2-starter/ai/icen/fw/web/spring/boot2/ForbiddenJdbc.kt:3"))
        assertTrue(result.output.contains("forbidden prefix: java.sql."))
        assertTrue(result.output.contains("fileweft-web-spring-boot3-starter/ai/icen/fw/web/spring/boot3/ForbiddenServlet.java:3"))
        assertTrue(result.output.contains("forbidden prefix: jakarta.servlet."))
        assertTrue(result.output.contains("fileweft-web-spring-boot2-starter/ai/icen/fw/web/spring/boot2/WrongBootGeneration.kt:5"))
        assertTrue(result.output.contains("ai.icen.fw.starter.boot3.FileWeftAutoConfiguration (not approved for fileweft-web-spring-boot2-starter)"))
        assertTrue(result.output.contains("fileweft-web-spring-boot2-starter/ai/icen/fw/web/spring/boot2/UnscopedStarterReference.kt:3"))
        assertTrue(result.output.contains("ai.icen.fw.starter.boot2.FileWeftAutoConfiguration (not approved for fileweft-web-spring-boot2-starter)"))
    }

    private fun writeProject(projectDir: File) {
        writeFile(
            projectDir,
            "settings.gradle.kts",
            """
            rootProject.name = "architecture-guard-fixture"
            include(":fileweft-core")
            include(":fileweft-adapter")
            include(":fileweft-web-api")
            include(":fileweft-web-runtime")
            include(":fileweft-web-spring-boot2-starter")
            include(":fileweft-web-spring-boot3-starter")
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
            "fileweft-adapter/build.gradle.kts",
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
        writeFile(
            projectDir,
            "fileweft-web-spring-boot2-starter/build.gradle.kts",
            """
            plugins {
                base
            }
            """.trimIndent(),
        )
        writeFile(
            projectDir,
            "fileweft-web-spring-boot3-starter/build.gradle.kts",
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
