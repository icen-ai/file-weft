plugins {
    id("fileweft.jvm17-library")
}

dependencies {
    api(project(":fileweft-runtime"))
    implementation(project(":fileweft-agent"))
    implementation(project(":fileweft-adapter"))
    implementation(project(":fileweft-adapter-micrometer"))
    implementation(libs.micrometer.core)
    implementation(project(":fileweft-persistence"))
    implementation(libs.jackson.databind)
    api(libs.spring.boot3.autoconfigure)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot3.test)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
