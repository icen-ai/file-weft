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

// Runtime suites rebuild the same PostgreSQL public schema when integration tests
// are enabled. Order only those suites; compilation and unrelated checks stay parallel.
// Keep the runtime order aligned with the root build's globally sorted matrix
// (java11, java21, java25, java8) to avoid contradictory ordering constraints.
val postgresRuntimeTestTasks = listOf("test", "java11Test", "java21Test", "java25Test", "java8Test")
postgresRuntimeTestTasks.drop(1).forEachIndexed { index, taskName ->
    tasks.named(taskName) {
        postgresRuntimeTestTasks.take(index + 1).forEach { previousTaskName ->
            mustRunAfter(tasks.named(previousTaskName))
        }
    }
}
