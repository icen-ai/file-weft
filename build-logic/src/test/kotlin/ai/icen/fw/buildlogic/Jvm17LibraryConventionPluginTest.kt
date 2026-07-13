package ai.icen.fw.buildlogic

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class Jvm17LibraryConventionPluginTest {
    @Test
    fun `registers the Java 17 runtime matrix`() = withTestProject { projectDir ->
        writeFile(
            projectDir,
            "settings.gradle.kts",
            """
            rootProject.name = "jvm17-convention-fixture"

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
                id("fileweft.jvm17-library")
            }

            tasks.register("assertExternalTestsPartitioned") {
                doLast {
                    listOf("test", "java17Test", "java21Test", "java25Test").forEach { taskName ->
                        val testTask = tasks.named<org.gradle.api.tasks.testing.Test>(taskName).get()
                        check("**/*IntegrationTest.class" in testTask.excludes) {
                            "External integration tests are not excluded from ${'$'}taskName"
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("assertExternalTestsPartitioned")
            .build()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("compatibilityTest", "--dry-run")
            .build()

        assertTrue(result.output.contains(":java17Test SKIPPED"))
        assertTrue(result.output.contains(":java21Test SKIPPED"))
        assertTrue(result.output.contains(":java25Test SKIPPED"))
        assertTrue(result.output.contains(":compatibilityTest SKIPPED"))
    }

    private fun writeFile(projectDir: File, relativePath: String, content: String) {
        val target = projectDir.resolve(relativePath)
        target.parentFile.mkdirs()
        target.writeText(content + "\n", StandardCharsets.UTF_8)
    }

    private fun withTestProject(action: (File) -> Unit) {
        val projectDir = Files.createTempDirectory("fileweft-jvm17-convention-").toFile()
        try {
            action(projectDir)
        } finally {
            assertTrue(projectDir.deleteRecursively(), "Could not remove temporary test project ${projectDir.absolutePath}")
        }
    }
}
