plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-domain"))
    // JDBC adapters implement application contracts and expose identifiers and
    // ObjectMapper in their public constructors, so Maven consumers need these
    // dependencies on their compile classpath as well as at runtime.
    api(project(":fileweft-application"))
    api(project(":fileweft-core"))
    implementation(libs.flyway.core)
    api(libs.jackson.databind)
    runtimeOnly(libs.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(project(":fileweft-agent"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
