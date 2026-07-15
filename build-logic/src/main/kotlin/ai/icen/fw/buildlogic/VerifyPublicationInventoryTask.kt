package ai.icen.fw.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Configuration-cache-safe task wrapper around the checked-in publication inventory contract. */
abstract class VerifyPublicationInventoryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inventoryFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleBuildFiles: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @TaskAction
    fun verifyInventory() {
        PublicationInventoryVerifier(
            inventoryFile.get().asFile,
            repositoryDirectory.get().asFile,
        ).verify()
    }
}
