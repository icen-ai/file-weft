package com.fileweft.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifySbomTaskTest {

    @Test
    fun `accepts a nonempty CycloneDX aggregate bom while storing the configuration cache`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeFile(projectDir, "build/reports/cyclonedx/bom.json", """{ "bomFormat" : "CycloneDX" }""")
        writeFile(projectDir, "build/reports/cyclonedx/bom.xml", "<bom/>" )

        val result = runner(projectDir).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifySbom")?.outcome)
        assertTrue(result.output.contains("Configuration cache entry stored."))
    }

    @Test
    fun `rejects a json bom that does not identify CycloneDX`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeFile(projectDir, "build/reports/cyclonedx/bom.json", """{ "bomFormat" : "SPDX" }""")
        writeFile(projectDir, "build/reports/cyclonedx/bom.xml", "<bom/>" )

        val result = runner(projectDir).buildAndFail()

        assertTrue(result.output.contains("does not declare the CycloneDX BOM format"))
    }

    private fun writeBuild(projectDir: File) {
        writeFile(projectDir, "settings.gradle.kts", "rootProject.name = \"verify-sbom-fixture\"")
        writeFile(
            projectDir,
            "build.gradle.kts",
            """
            plugins {
                id("fileweft.sbom-verification")
            }
            """.trimIndent(),
        )
    }

    private fun runner(projectDir: File): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments("--configuration-cache", "verifySbom")

    private fun writeFile(projectDir: File, relativePath: String, content: String) {
        val target = projectDir.resolve(relativePath)
        target.parentFile.mkdirs()
        target.writeText(content + "\n", StandardCharsets.UTF_8)
    }

    private fun withTestProject(action: (File) -> Unit) {
        val projectDir = Files.createTempDirectory("fileweft-verify-sbom-").toFile()
        try {
            action(projectDir)
        } finally {
            assertTrue(projectDir.deleteRecursively(), "Could not remove temporary test project ${projectDir.absolutePath}")
        }
    }
}
