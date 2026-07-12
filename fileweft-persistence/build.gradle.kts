plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-domain"))
    implementation(project(":fileweft-application"))
    implementation(project(":fileweft-core"))
    implementation(libs.flyway.core)
    implementation(libs.jackson.databind)
    runtimeOnly(libs.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(project(":fileweft-agent"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
