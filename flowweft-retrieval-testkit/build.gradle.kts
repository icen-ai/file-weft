plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":flowweft-retrieval-api"))
    api(project(":flowweft-retrieval-spi"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
