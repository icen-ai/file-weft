package ai.icen.fw.buildlogic

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Jvm8LibraryConventionPluginTest {

    @Test
    fun `registers the Java 8 baseline runtime matrix without slowing normal check`() = withTestProject { projectDir ->
        writeFile(
            projectDir,
            "settings.gradle.kts",
            """
            rootProject.name = "jvm8-convention-fixture"

            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
            }
            """.trimIndent(),
        )
        writeFile(
            projectDir,
            "build.gradle.kts",
            """
            plugins {
                id("fileweft.jvm8-library")
            }
            """.trimIndent(),
        )

        val normalCheck = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("--configuration-cache", "check", "--dry-run")
            .build()

        assertTrue(normalCheck.output.contains(":java8Test SKIPPED"))
        assertFalse(normalCheck.output.contains(":java11Test SKIPPED"))
        assertTrue(normalCheck.output.contains(":check SKIPPED"))

        val matrix = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("compatibilityTest", "--dry-run")
            .build()

        assertTrue(matrix.output.contains(":java8Test SKIPPED"))
        assertTrue(matrix.output.contains(":java11Test SKIPPED"))
        assertTrue(matrix.output.contains(":java21Test SKIPPED"))
        assertTrue(matrix.output.contains(":java25Test SKIPPED"))
        assertTrue(matrix.output.contains(":compatibilityTest SKIPPED"))
    }

    private fun writeFile(projectDir: File, relativePath: String, content: String) {
        val target = projectDir.resolve(relativePath)
        target.parentFile.mkdirs()
        target.writeText(content + "\n", StandardCharsets.UTF_8)
    }

    private fun withTestProject(action: (File) -> Unit) {
        val projectDir = Files.createTempDirectory("fileweft-jvm8-convention-").toFile()
        try {
            action(projectDir)
        } finally {
            assertTrue(projectDir.deleteRecursively(), "Could not remove temporary test project ${projectDir.absolutePath}")
        }
    }
}
