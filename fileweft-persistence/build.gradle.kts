plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-domain"))
    implementation(project(":fileweft-application"))
    implementation(project(":fileweft-core"))
    implementation(libs.flyway.core)
    runtimeOnly(libs.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
