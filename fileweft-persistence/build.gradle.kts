plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-domain"))
    implementation(project(":fileweft-core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
