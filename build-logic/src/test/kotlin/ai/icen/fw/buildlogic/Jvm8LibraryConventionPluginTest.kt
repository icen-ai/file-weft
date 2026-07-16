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

            dependencies {
                testImplementation("com.h2database:h2:2.3.232")
            }

            tasks.register("assertExternalTestsPartitioned") {
                doLast {
                    listOf("test", "java8Test", "java11Test", "java21Test", "java25Test").forEach { taskName ->
                        val testTask = tasks.named<org.gradle.api.tasks.testing.Test>(taskName).get()
                        check("**/*IntegrationTest.class" in testTask.excludes) {
                            "External integration tests are not excluded from ${'$'}taskName"
                        }
                    }
                }
            }

            tasks.register("assertJava8H2Compatibility") {
                doLast {
                    val classpathNames = tasks.named<org.gradle.api.tasks.testing.Test>("java8Test")
                        .get()
                        .classpath
                        .files
                        .map { it.name }
                    check("h2-1.4.200.jar" in classpathNames) {
                        "Java 8 test classpath must contain the Java 8-compatible H2 runtime."
                    }
                    check("h2-2.3.232.jar" !in classpathNames) {
                        "Java 8 test classpath must not contain the Java 11+ H2 runtime."
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

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("assertJava8H2Compatibility")
            .build()

        val normalCheck = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            // This assertion inspects only the task graph. Configuration-cache serialization
            // resolves the Java 8 launcher and would make the fixture depend on a host JDK 8.
            .withArguments("check", "--dry-run")
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
