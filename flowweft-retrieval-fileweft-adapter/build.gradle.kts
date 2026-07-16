plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":flowweft-retrieval-api"))
    api(project(":fileweft-application"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
