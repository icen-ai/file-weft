package ai.icen.fw.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.w3c.dom.Element
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerateReleaseSbomTaskTest {

    @Test
    fun `derives licensed release boms while retaining third party components`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeAggregateSboms(projectDir)

        val first = runner(projectDir, "generateReleaseSbom").build()
        val second = runner(projectDir, "generateReleaseSbom").build()

        assertEquals(TaskOutcome.SUCCESS, first.task(":generateReleaseSbom")?.outcome)
        assertTrue(first.output.contains("Configuration cache entry stored."))
        assertTrue(second.output.contains("Configuration cache entry reused."))
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateReleaseSbom")?.outcome)

        val releaseJson = projectDir.resolve("build/reports/cyclonedx-release/bom.json")
        val releaseXml = projectDir.resolve("build/reports/cyclonedx-release/bom.xml")
        val firstReleaseJson = releaseJson.readBytes()
        val firstReleaseXml = releaseXml.readBytes()
        replaceAggregateVolatileMetadata(projectDir)
        val regenerated = runner(projectDir, "generateReleaseSbom").build()
        val verification = runner(projectDir, "verifySbom").build()

        assertEquals(TaskOutcome.SUCCESS, regenerated.task(":generateReleaseSbom")?.outcome)
        assertTrue(firstReleaseJson.contentEquals(releaseJson.readBytes()))
        assertTrue(firstReleaseXml.contentEquals(releaseXml.readBytes()))
        assertEquals(TaskOutcome.SUCCESS, verification.task(":verifySbom")?.outcome)

        val json = JsonSlurper().parse(releaseJson, StandardCharsets.UTF_8.name()) as Map<*, *>
        assertFalse(json.containsKey("serialNumber"))
        val metadata = json["metadata"] as Map<*, *>
        assertFalse(metadata.containsKey("timestamp"))
        val rootComponent = metadata["component"] as Map<*, *>
        assertTrue(hasApacheJsonLicense(rootComponent))
        assertFalse(metadata.containsKey("licenses"))
        val components = (json["components"] as List<*>).filterIsInstance<Map<*, *>>()
        assertEquals(
            setOf("fileweft-core", "fileweft-spi"),
            components.filter { component -> component["group"] == "ai.icen" }
                .map { component -> component["name"] }
                .toSet(),
        )
        assertTrue(components.any { component -> component["name"] == "external-lib" })
        assertTrue(
            components.filter { component -> component["group"] == "ai.icen" }
                .all(::hasApacheJsonLicense),
        )
        val dependencies = (json["dependencies"] as List<*>).filterIsInstance<Map<*, *>>()
        assertFalse(dependencies.any { dependency -> dependency["ref"].toString().contains("fileweft-dev") })
        assertFalse(
            dependencies.flatMap { dependency -> (dependency["dependsOn"] as? List<*>).orEmpty() }
                .any { reference -> reference.toString().contains("fileweft-dev") },
        )

        val document = GenerateReleaseSbomTask.secureDocumentBuilderFactory().newDocumentBuilder().parse(releaseXml)
        assertFalse(document.documentElement.hasAttribute("serialNumber"))
        val xmlMetadata = GenerateReleaseSbomTask.directChild(document.documentElement, "metadata")!!
        assertTrue(GenerateReleaseSbomTask.directChildren(xmlMetadata, "timestamp").isEmpty())
        val xmlRoot = GenerateReleaseSbomTask.directChild(xmlMetadata, "component")!!
        assertTrue(hasApacheXmlLicense(xmlRoot))
        assertTrue(GenerateReleaseSbomTask.directChildren(xmlMetadata, "licenses").isEmpty())
        val xmlComponents = GenerateReleaseSbomTask.directChildren(
            GenerateReleaseSbomTask.directChild(document.documentElement, "components")!!,
            "component",
        )
        assertEquals(
            setOf("fileweft-core", "fileweft-spi"),
            xmlComponents.filter { component ->
                GenerateReleaseSbomTask.directChildText(component, "group") == "ai.icen"
            }.map { component -> GenerateReleaseSbomTask.directChildText(component, "name") }.toSet(),
        )
        assertTrue(xmlComponents.any { component -> GenerateReleaseSbomTask.directChildText(component, "name") == "external-lib" })
    }

    @Test
    fun `fails when an aggregate bom omits a publishable module`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeAggregateSboms(projectDir)
        val jsonFile = projectDir.resolve("build/reports/cyclonedx/bom.json")
        jsonFile.writeText(jsonFile.readText(StandardCharsets.UTF_8).replace("fileweft-spi", "fileweft-other"), StandardCharsets.UTF_8)

        val result = runner(projectDir, "generateReleaseSbom").buildAndFail()

        assertTrue(result.output.contains("missing publishable modules: [fileweft-spi]"))
    }

    @Test
    fun `fails safely on an aggregate xml doctype`() = withTestProject { projectDir ->
        writeBuild(projectDir)
        writeAggregateSboms(projectDir)
        writeFile(
            projectDir,
            "build/reports/cyclonedx/bom.xml",
            """
            <!DOCTYPE bom [<!ENTITY xxe SYSTEM "file:///definitely-not-readable">]>
            <bom xmlns="http://cyclonedx.org/schema/bom/1.6"><metadata>&xxe;</metadata></bom>
            """.trimIndent(),
        )

        val result = runner(projectDir, "generateReleaseSbom").buildAndFail()

        assertTrue(result.output.contains("DOCTYPE is disallowed") || result.output.contains("disallow-doctype-decl"))
    }

    private fun writeBuild(projectDir: File) {
        writeFile(projectDir, "settings.gradle.kts", "rootProject.name = \"release-sbom-fixture\"")
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

    private fun writeAggregateSboms(projectDir: File) {
        writeFile(projectDir, "build/reports/cyclonedx/bom.json", aggregateJson())
        writeFile(projectDir, "build/reports/cyclonedx/bom.xml", aggregateXml())
    }

    private fun replaceAggregateVolatileMetadata(projectDir: File) {
        val replacements = mapOf(
            "urn:uuid:11111111-1111-4111-8111-111111111111" to
                "urn:uuid:22222222-2222-4222-8222-222222222222",
            "2026-07-13T00:00:00Z" to "2030-01-01T12:34:56Z",
        )
        listOf(
            projectDir.resolve("build/reports/cyclonedx/bom.json"),
            projectDir.resolve("build/reports/cyclonedx/bom.xml"),
        ).forEach { aggregate ->
            val changed = replacements.entries.fold(aggregate.readText(StandardCharsets.UTF_8)) { content, replacement ->
                content.replace(replacement.key, replacement.value)
            }
            aggregate.writeText(changed, StandardCharsets.UTF_8)
        }
    }

    private fun aggregateJson(): String =
        """
        {
          "bomFormat":"CycloneDX",
          "serialNumber":"urn:uuid:11111111-1111-4111-8111-111111111111",
          "metadata":{
            "timestamp":"2026-07-13T00:00:00Z",
            "component":{"type":"library","bom-ref":"pkg:maven/ai.icen/release-sbom-fixture@0.0.1?project_path=%3A","group":"ai.icen","name":"release-sbom-fixture","version":"0.0.1"},
            "licenses":[{"license":{"name":"Apache-2.0","url":"https://www.apache.org/licenses/LICENSE-2.0.txt"}}]
          },
          "components":[
            {"type":"library","bom-ref":"pkg:maven/ai.icen/fileweft-core@0.0.1?project_path=%3Afileweft-core","group":"ai.icen","name":"fileweft-core","version":"0.0.1"},
            {"type":"library","bom-ref":"pkg:maven/ai.icen/fileweft-dev@0.0.1?project_path=%3Afileweft-dev","group":"ai.icen","name":"fileweft-dev","version":"0.0.1"},
            {"type":"library","bom-ref":"pkg:maven/ai.icen/fileweft-spi@0.0.1?project_path=%3Afileweft-spi","group":"ai.icen","name":"fileweft-spi","version":"0.0.1"},
            {"type":"library","bom-ref":"pkg:maven/org.example/external-lib@1.2.3","group":"org.example","name":"external-lib","version":"1.2.3"}
          ],
          "dependencies":[
            {"ref":"pkg:maven/ai.icen/release-sbom-fixture@0.0.1?project_path=%3A","dependsOn":["pkg:maven/ai.icen/fileweft-core@0.0.1?project_path=%3Afileweft-core","pkg:maven/ai.icen/fileweft-dev@0.0.1?project_path=%3Afileweft-dev","pkg:maven/ai.icen/fileweft-spi@0.0.1?project_path=%3Afileweft-spi"]},
            {"ref":"pkg:maven/ai.icen/fileweft-core@0.0.1?project_path=%3Afileweft-core","dependsOn":["pkg:maven/org.example/external-lib@1.2.3"]},
            {"ref":"pkg:maven/ai.icen/fileweft-dev@0.0.1?project_path=%3Afileweft-dev","dependsOn":["pkg:maven/org.example/external-lib@1.2.3"]},
            {"ref":"pkg:maven/ai.icen/fileweft-spi@0.0.1?project_path=%3Afileweft-spi","dependsOn":[]},
            {"ref":"pkg:maven/org.example/external-lib@1.2.3","dependsOn":[]}
          ]
        }
        """.trimIndent()

    private fun aggregateXml(): String =
        """
        <bom xmlns="http://cyclonedx.org/schema/bom/1.6" serialNumber="urn:uuid:11111111-1111-4111-8111-111111111111">
          <metadata>
            <timestamp>2026-07-13T00:00:00Z</timestamp>
            <component type="library" bom-ref="pkg:maven/ai.icen/release-sbom-fixture@0.0.1?project_path=%3A"><group>ai.icen</group><name>release-sbom-fixture</name><version>0.0.1</version></component>
            <licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>
          </metadata>
          <components>
            <component type="library" bom-ref="pkg:maven/ai.icen/fileweft-core@0.0.1?project_path=%3Afileweft-core"><group>ai.icen</group><name>fileweft-core</name><version>0.0.1</version></component>
            <component type="library" bom-ref="pkg:maven/ai.icen/fileweft-dev@0.0.1?project_path=%3Afileweft-dev"><group>ai.icen</group><name>fileweft-dev</name><version>0.0.1</version></component>
            <component type="library" bom-ref="pkg:maven/ai.icen/fileweft-spi@0.0.1?project_path=%3Afileweft-spi"><group>ai.icen</group><name>fileweft-spi</name><version>0.0.1</version></component>
            <component type="library" bom-ref="pkg:maven/org.example/external-lib@1.2.3"><group>org.example</group><name>external-lib</name><version>1.2.3</version></component>
          </components>
          <dependencies>
            <dependency ref="pkg:maven/ai.icen/release-sbom-fixture@0.0.1?project_path=%3A"><dependency ref="pkg:maven/ai.icen/fileweft-core@0.0.1?project_path=%3Afileweft-core"/><dependency ref="pkg:maven/ai.icen/fileweft-dev@0.0.1?project_path=%3Afileweft-dev"/><dependency ref="pkg:maven/ai.icen/fileweft-spi@0.0.1?project_path=%3Afileweft-spi"/></dependency>
            <dependency ref="pkg:maven/ai.icen/fileweft-core@0.0.1?project_path=%3Afileweft-core"><dependency ref="pkg:maven/org.example/external-lib@1.2.3"/></dependency>
            <dependency ref="pkg:maven/ai.icen/fileweft-dev@0.0.1?project_path=%3Afileweft-dev"><dependency ref="pkg:maven/org.example/external-lib@1.2.3"/></dependency>
            <dependency ref="pkg:maven/ai.icen/fileweft-spi@0.0.1?project_path=%3Afileweft-spi"/>
            <dependency ref="pkg:maven/org.example/external-lib@1.2.3"/>
          </dependencies>
        </bom>
        """.trimIndent()

    private fun hasApacheJsonLicense(component: Map<*, *>): Boolean =
        (component["licenses"] as? List<*>)
            .orEmpty()
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { choice -> choice["license"] as? Map<*, *> }
            .any { license ->
                license["name"] == "Apache-2.0" &&
                    license["url"] == "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }

    private fun hasApacheXmlLicense(component: Element): Boolean =
        GenerateReleaseSbomTask.directChild(component, "licenses")
            ?.let { licenses -> GenerateReleaseSbomTask.directChildren(licenses, "license") }
            .orEmpty()
            .any { license ->
                GenerateReleaseSbomTask.directChildText(license, "name") == "Apache-2.0" &&
                    GenerateReleaseSbomTask.directChildText(license, "url") ==
                    "https://www.apache.org/licenses/LICENSE-2.0.txt"
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
        val projectDir = Files.createTempDirectory("fileweft-generate-sbom-").toFile()
        try {
            action(projectDir)
        } finally {
            assertTrue(projectDir.deleteRecursively(), "Could not remove temporary test project ${projectDir.absolutePath}")
        }
    }
}
