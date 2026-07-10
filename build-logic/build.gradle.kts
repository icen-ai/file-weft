plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21")
}

gradlePlugin {
    plugins {
        register("fileweftJvm8Library") {
            id = "fileweft.jvm8-library"
            implementationClass = "com.fileweft.buildlogic.Jvm8LibraryConventionPlugin"
        }
        register("fileweftJvm17Library") {
            id = "fileweft.jvm17-library"
            implementationClass = "com.fileweft.buildlogic.Jvm17LibraryConventionPlugin"
        }
    }
}
