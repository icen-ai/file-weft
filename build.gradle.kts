plugins {
    id("fileweft.architecture-guard")
}

group = "com.fileweft"
version = "0.1.0-SNAPSHOT"

allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
