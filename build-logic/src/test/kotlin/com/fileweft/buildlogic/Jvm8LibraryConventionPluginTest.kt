package com.fileweft.buildlogic

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class Jvm8LibraryConventionPluginTest {

    @Test
    fun `check includes the Java 8 runtime verification task`() = withTestProject { projectDir ->
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

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("--configuration-cache", "check", "--dry-run")
            .build()

        assertTrue(result.output.contains(":java8Test SKIPPED"))
        assertTrue(result.output.contains(":check SKIPPED"))
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
