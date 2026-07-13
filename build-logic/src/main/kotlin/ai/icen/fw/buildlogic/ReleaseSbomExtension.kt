package ai.icen.fw.buildlogic

import org.gradle.api.provider.ListProperty

/** Configuration for the public FileWeft release SBOM. */
abstract class ReleaseSbomExtension {
    abstract val publishableModuleNames: ListProperty<String>
}
