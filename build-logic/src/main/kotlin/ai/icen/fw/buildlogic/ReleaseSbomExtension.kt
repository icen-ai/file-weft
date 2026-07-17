package ai.icen.fw.buildlogic

import org.gradle.api.provider.ListProperty

/** Configuration for the public FlowWeft release SBOM. */
abstract class ReleaseSbomExtension {
    abstract val publishableModuleNames: ListProperty<String>
}
