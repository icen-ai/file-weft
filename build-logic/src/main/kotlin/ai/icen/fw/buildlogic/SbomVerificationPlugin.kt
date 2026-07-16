package ai.icen.fw.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class SbomVerificationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "flowWeftReleaseSbom",
            ReleaseSbomExtension::class.java,
        )
        val aggregateJson = project.layout.buildDirectory.file("reports/cyclonedx/bom.json")
        val aggregateXml = project.layout.buildDirectory.file("reports/cyclonedx/bom.xml")
        val releaseJson = project.layout.buildDirectory.file("reports/cyclonedx-release/bom.json")
        val releaseXml = project.layout.buildDirectory.file("reports/cyclonedx-release/bom.xml")
        val generateReleaseSbom = project.tasks.register(
            "generateReleaseSbom",
            GenerateReleaseSbomTask::class.java,
        ) {
            group = "build"
            description = "Derives the public release CycloneDX JSON and XML SBOMs from the aggregate BOM."
            aggregateJsonSbom.set(aggregateJson)
            aggregateXmlSbom.set(aggregateXml)
            expectedGroup.convention(project.provider { project.group.toString() })
            expectedName.convention(project.provider { project.name })
            expectedVersion.convention(project.provider { project.version.toString() })
            publishableModuleNames.convention(extension.publishableModuleNames)
            licenseName.convention("Apache-2.0")
            licenseUrl.convention("https://www.apache.org/licenses/LICENSE-2.0.txt")
            releaseJsonSbom.set(releaseJson)
            releaseXmlSbom.set(releaseXml)
        }
        val verifySbom = project.tasks.register("verifySbom", VerifySbomTask::class.java) {
            group = "verification"
            description = "Generates and verifies the public release CycloneDX JSON and XML SBOMs."
            jsonSbom.set(releaseJson)
            xmlSbom.set(releaseXml)
            expectedGroup.convention(project.provider { project.group.toString() })
            expectedName.convention(project.provider { project.name })
            expectedVersion.convention(project.provider { project.version.toString() })
            expectedModuleNames.convention(extension.publishableModuleNames)
            expectedLicenseName.convention("Apache-2.0")
            expectedLicenseUrl.convention("https://www.apache.org/licenses/LICENSE-2.0.txt")
        }

        project.pluginManager.withPlugin("org.cyclonedx.bom") {
            generateReleaseSbom.configure { dependsOn("cyclonedxBom") }
            verifySbom.configure { dependsOn(generateReleaseSbom) }
            project.gradle.projectsEvaluated {
                project.allprojects.forEach { candidate ->
                    candidate.tasks.named("cyclonedxDirectBom").get()
                }
            }
        }
    }
}
