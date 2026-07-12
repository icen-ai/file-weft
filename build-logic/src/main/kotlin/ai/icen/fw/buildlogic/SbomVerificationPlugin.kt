package ai.icen.fw.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class SbomVerificationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val sbomJson = project.layout.buildDirectory.file("reports/cyclonedx/bom.json")
        val sbomXml = project.layout.buildDirectory.file("reports/cyclonedx/bom.xml")
        val verifySbom = project.tasks.register("verifySbom", VerifySbomTask::class.java) {
            group = "verification"
            description = "Generates and verifies aggregate CycloneDX JSON and XML software bill of materials files."
            jsonSbom.set(sbomJson)
            xmlSbom.set(sbomXml)
        }

        project.pluginManager.withPlugin("org.cyclonedx.bom") {
            verifySbom.configure {
                dependsOn("cyclonedxBom")
            }
            project.gradle.projectsEvaluated {
                project.allprojects.forEach { candidate ->
                    candidate.tasks.named("cyclonedxDirectBom").get()
                }
            }
        }
    }
}
