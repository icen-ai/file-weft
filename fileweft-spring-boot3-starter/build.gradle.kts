plugins {
    id("fileweft.jvm17-library")
}

dependencies {
    api(project(":fileweft-runtime"))
    // Auto-configuration bean methods are public JVM API. Keep every type used
    // in their signatures available to Java and Kotlin consumers.
    api(project(":fileweft-agent"))
    api(project(":fileweft-adapter"))
    api(project(":fileweft-adapter-micrometer"))
    api(libs.micrometer.core)
    api(project(":fileweft-persistence"))
    api(libs.jackson.databind)
    api(libs.spring.boot3.autoconfigure)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot3.test)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
