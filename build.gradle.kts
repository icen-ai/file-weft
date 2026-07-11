plugins {
    id("fileweft.architecture-guard")
    id("org.cyclonedx.bom") version "3.2.4"
    id("fileweft.sbom-verification")
}

group = "com.fileweft"
version = "0.1.0-SNAPSHOT"

allprojects {
    group = rootProject.group
    version = rootProject.version
}

val verifyFileWeftBuildLogic = tasks.register("verifyFileWeftBuildLogic") {
    group = "verification"
    description = "Runs the included build-logic verification suite."
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

val compatibilityCheck = tasks.register("compatibilityCheck") {
    group = "verification"
    description = "Runs the supported Java runtime matrices for all FileWeft modules."
}

subprojects {
    val moduleCompatibilityTaskPath = "$path:compatibilityTest"
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.matching { task -> task.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyFileWeftBuildLogic"))
    }

    listOf("fileweft.jvm8-library", "fileweft.jvm17-library").forEach { conventionPluginId ->
        pluginManager.withPlugin(conventionPluginId) {
            rootProject.tasks.named("compatibilityCheck").configure {
                dependsOn(moduleCompatibilityTaskPath)
            }
        }
    }
}
