import org.gradle.api.tasks.testing.Test

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
    runtimeOnly(libs.mysql.connector.j)
    testImplementation(platform(libs.junit.bom))
    testImplementation(project(":fileweft-agent"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.postgresql)
    testImplementation(libs.mysql.connector.j)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Kingbase ES JDBC driver is not published to Maven Central. Consumers must
    // provide it through their own repository or local file dependency. The
    // integration test below skips itself when the driver is not on the classpath.
}

// Runtime suites rebuild the same PostgreSQL public schema when integration tests
// are enabled. Order only those suites; compilation and unrelated checks stay parallel.
// Keep the runtime order aligned with the root build's globally sorted matrix
// (java11, java21, java25, java8) to avoid contradictory ordering constraints.
val postgresRuntimeTestTasks = listOf("test", "java11Test", "java21Test", "java25Test", "java8Test")
val runPostgresIntegration = providers.environmentVariable("FILEWEFT_RUN_POSTGRES_TESTS")
    .map { value -> value == "true" }
    .orElse(false)

tasks.withType<Test>().configureEach {
    inputs.property("fileWeftRunPostgresTests", runPostgresIntegration)
    if (runPostgresIntegration.get()) {
        doNotTrackState("The enabled PostgreSQL integration suite must execute against the current database state.")
    }
}

postgresRuntimeTestTasks.drop(1).forEachIndexed { index, taskName ->
    tasks.named(taskName) {
        postgresRuntimeTestTasks.take(index + 1).forEach { previousTaskName ->
            mustRunAfter(tasks.named(previousTaskName))
        }
    }
}
