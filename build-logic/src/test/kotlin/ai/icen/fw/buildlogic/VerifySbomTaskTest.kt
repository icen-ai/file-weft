package ai.icen.fw.buildlogic

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
    fun `accepts matching release boms and reuses the configuration cache`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeValidSboms(projectDir)

        val first = runner(projectDir).build()
        val second = runner(projectDir).build()

        assertEquals(TaskOutcome.SUCCESS, first.task(":verifySbom")?.outcome)
        assertTrue(first.output.contains("Configuration cache entry stored."))
        assertTrue(second.output.contains("Configuration cache entry reused."))
    }

    @Test
    fun `rejects a json bom that does not identify CycloneDX`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeValidSboms(projectDir)
        writeJson(projectDir, validJson().replace("CycloneDX", "SPDX"))

        val result = runner(projectDir).buildAndFail()

        assertTrue(result.output.contains("does not declare the CycloneDX BOM format"))
    }

    @Test
    fun `rejects unsafe xml before resolving external entities`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeValidSboms(projectDir)
        writeXml(
            projectDir,
            """
            <!DOCTYPE bom [<!ENTITY xxe SYSTEM "file:///definitely-not-readable">]>
            <bom xmlns="http://cyclonedx.org/schema/bom/1.6"><metadata>&xxe;</metadata></bom>
            """.trimIndent(),
        )

        val result = runner(projectDir).buildAndFail()

        assertTrue(result.output.contains("DOCTYPE is disallowed") || result.output.contains("disallow-doctype-decl"))
    }

    @Test
    fun `rejects a root component without the release license`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeValidSboms(projectDir)
        writeJson(projectDir, validJson().replaceFirst(licenseJson(), "\"licenses\":[]"))

        val result = runner(projectDir).buildAndFail()

        assertTrue(result.output.contains("component root does not declare Apache-2.0"))
    }

    @Test
    fun `rejects a published module without the release license`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeValidSboms(projectDir)
        val json = validJson()
        val coreStart = json.indexOf("\"name\":\"fileweft-core\"")
        val licenseStart = json.indexOf(licenseJson(), coreStart)
        writeJson(projectDir, json.replaceRange(licenseStart, licenseStart + licenseJson().length, "\"licenses\":[]"))

        val result = runner(projectDir).buildAndFail()

        assertTrue(result.output.contains("module fileweft-core does not declare Apache-2.0"))
    }

    @Test
    fun `rejects missing and unexpected internal modules`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeValidSboms(projectDir)
        writeJson(
            projectDir,
            validJson()
                .replace("fileweft-spi", "fileweft-dev")
                .replace("%3Afileweft-spi", "%3Afileweft-dev"),
        )

        val result = runner(projectDir).buildAndFail()

        assertTrue(result.output.contains("missing=[fileweft-spi]"))
        assertTrue(result.output.contains("unexpected=[fileweft-dev]"))
    }

    @Test
    fun `rejects a published module at the wrong version`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeValidSboms(projectDir)
        val json = validJson()
        val spiStart = json.indexOf("\"name\":\"fileweft-spi\"")
        val versionToken = "\"version\":\"0.0.1\""
        val versionStart = json.indexOf(versionToken, spiStart)
        writeJson(
            projectDir,
            json.replaceRange(versionStart, versionStart + versionToken.length, "\"version\":\"9.9.9\""),
        )

        val result = runner(projectDir).buildAndFail()

        assertTrue(result.output.contains("fileweft-spi has the wrong version"))
    }

    @Test
    fun `rejects component coordinate differences between json and xml`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeValidSboms(projectDir)
        writeXml(projectDir, validXml().replace("external-lib</name>", "other-lib</name>"))

        val result = runner(projectDir).buildAndFail()

        assertTrue(result.output.contains("JSON and XML SBOM component coordinates differ"))
    }

    @Test
    fun `rejects dependency graph differences between json and xml`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeValidSboms(projectDir)
        writeXml(
            projectDir,
            validXml().replace(
                "<dependency ref=\"pkg:maven/org.example/external-lib@1.2.3\"/>",
                "",
            ),
        )

        val result = runner(projectDir).buildAndFail()

        assertTrue(result.output.contains("JSON and XML SBOM dependency graphs differ"))
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

            group = "ai.icen"
            version = "0.0.1"

            extensions.configure<ai.icen.fw.buildlogic.ReleaseSbomExtension>("fileWeftReleaseSbom") {
                publishableModuleNames.set(listOf("fileweft-core", "fileweft-spi"))
            }
            """.trimIndent(),
        )
    }

    private fun writeValidSboms(projectDir: File) {
        writeJson(projectDir, validJson())
        writeXml(projectDir, validXml())
    }

    private fun writeJson(projectDir: File, content: String) =
        writeFile(projectDir, "build/reports/cyclonedx-release/bom.json", content)

    private fun writeXml(projectDir: File, content: String) =
        writeFile(projectDir, "build/reports/cyclonedx-release/bom.xml", content)

    private fun licenseJson(): String =
        "\"licenses\":[{\"license\":{\"name\":\"Apache-2.0\",\"url\":\"https://www.apache.org/licenses/LICENSE-2.0.txt\"}}]"

    private fun validJson(): String =
        """
        {
          "bomFormat":"CycloneDX",
          "metadata":{"component":{
            "group":"ai.icen","name":"verify-sbom-fixture","version":"0.0.1",${licenseJson()}
          }},
          "components":[
            {"type":"library","bom-ref":"pkg:maven/ai.icen/fileweft-core@0.0.1?project_path=%3Afileweft-core","group":"ai.icen","name":"fileweft-core","version":"0.0.1",${licenseJson()}},
            {"type":"library","bom-ref":"pkg:maven/ai.icen/fileweft-spi@0.0.1?project_path=%3Afileweft-spi","group":"ai.icen","name":"fileweft-spi","version":"0.0.1",${licenseJson()}},
            {"type":"library","bom-ref":"pkg:maven/org.example/external-lib@1.2.3","group":"org.example","name":"external-lib","version":"1.2.3"}
          ],
          "dependencies":[
            {"ref":"pkg:maven/ai.icen/fileweft@0.0.1?project_path=%3A","dependsOn":["pkg:maven/ai.icen/fileweft-core@0.0.1?project_path=%3Afileweft-core","pkg:maven/ai.icen/fileweft-spi@0.0.1?project_path=%3Afileweft-spi"]},
            {"ref":"pkg:maven/ai.icen/fileweft-core@0.0.1?project_path=%3Afileweft-core","dependsOn":["pkg:maven/org.example/external-lib@1.2.3"]},
            {"ref":"pkg:maven/ai.icen/fileweft-spi@0.0.1?project_path=%3Afileweft-spi","dependsOn":[]},
            {"ref":"pkg:maven/org.example/external-lib@1.2.3","dependsOn":[]}
          ]
        }
        """.trimIndent()

    private fun validXml(): String =
        """
        <bom xmlns="http://cyclonedx.org/schema/bom/1.6">
          <metadata><component type="library">
            <group>ai.icen</group><name>verify-sbom-fixture</name><version>0.0.1</version>${licenseXml()}
          </component></metadata>
          <components>
            <component type="library" bom-ref="pkg:maven/ai.icen/fileweft-core@0.0.1?project_path=%3Afileweft-core"><group>ai.icen</group><name>fileweft-core</name><version>0.0.1</version>${licenseXml()}</component>
            <component type="library" bom-ref="pkg:maven/ai.icen/fileweft-spi@0.0.1?project_path=%3Afileweft-spi"><group>ai.icen</group><name>fileweft-spi</name><version>0.0.1</version>${licenseXml()}</component>
            <component type="library" bom-ref="pkg:maven/org.example/external-lib@1.2.3"><group>org.example</group><name>external-lib</name><version>1.2.3</version></component>
          </components>
          <dependencies>
            <dependency ref="pkg:maven/ai.icen/fileweft@0.0.1?project_path=%3A"><dependency ref="pkg:maven/ai.icen/fileweft-core@0.0.1?project_path=%3Afileweft-core"/><dependency ref="pkg:maven/ai.icen/fileweft-spi@0.0.1?project_path=%3Afileweft-spi"/></dependency>
            <dependency ref="pkg:maven/ai.icen/fileweft-core@0.0.1?project_path=%3Afileweft-core"><dependency ref="pkg:maven/org.example/external-lib@1.2.3"/></dependency>
            <dependency ref="pkg:maven/ai.icen/fileweft-spi@0.0.1?project_path=%3Afileweft-spi"/>
            <dependency ref="pkg:maven/org.example/external-lib@1.2.3"/>
          </dependencies>
        </bom>
        """.trimIndent()

    private fun licenseXml(): String =
        "<licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>"

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
