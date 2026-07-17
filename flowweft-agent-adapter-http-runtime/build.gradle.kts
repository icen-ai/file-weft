plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":flowweft-agent-adapter-http"))
    api(project(":flowweft-agent-adapter-http-okhttp"))
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
