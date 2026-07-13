plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-spi"))
    // OpenTelemetry API is part of the public adapter constructor contract.
    api(libs.opentelemetry.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.opentelemetry.sdk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
