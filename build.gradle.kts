plugins {
    id("fileweft.architecture-guard")
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

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.matching { task -> task.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyFileWeftBuildLogic"))
    }
}
