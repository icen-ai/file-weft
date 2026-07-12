package ai.icen.fw.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets

/** Verifies that the aggregate release SBOMs exist and identify themselves as CycloneDX. */
abstract class VerifySbomTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jsonSbom: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val xmlSbom: RegularFileProperty

    @TaskAction
    fun verify() {
        listOf(jsonSbom.get().asFile, xmlSbom.get().asFile).forEach { file ->
            check(file.isFile && file.length() > 0L) { "Expected SBOM output was not generated: ${file.absolutePath}" }
        }
        check(BOM_FORMAT.containsMatchIn(jsonSbom.get().asFile.readText(StandardCharsets.UTF_8))) {
            "Aggregate JSON SBOM does not declare the CycloneDX BOM format."
        }
    }

    private companion object {
        val BOM_FORMAT: Regex = Regex("\\\"bomFormat\\\"\\s*:\\s*\\\"CycloneDX\\\"")
    }
}
