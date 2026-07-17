import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":flowweft-workflow-runtime"))

    // Flyway 9 declares an old Jackson TOML stack. Align its runtime transitives
    // with FlowWeft's maintained Jackson line rather than publishing a known-
    // vulnerable transitive closure from this JDBC adapter.
    implementation(platform(libs.jackson.bom))
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.mysql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.mysql.connector.j)
    runtimeOnly(libs.kingbase.jdbc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val testSourceSet = sourceSets.named("test")
val integrationJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.named<Test>("test") {
    exclude("**/*IntegrationTest.class")
}

fun registerWorkflowDatabaseIntegrationTest(
    taskName: String,
    descriptionText: String,
    testPattern: String,
    enabledEnvironmentVariable: String,
): org.gradle.api.tasks.TaskProvider<Test> = tasks.register<Test>(taskName) {
    group = "verification"
    description = descriptionText
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform()
    javaLauncher.set(integrationJavaLauncher)
    include(testPattern)
    maxParallelForks = 1
    val enabled = providers.environmentVariable(enabledEnvironmentVariable)
        .map { value -> value == "true" }
        .orElse(false)
    inputs.property(enabledEnvironmentVariable, enabled)
    doNotTrackState("The Workflow database contract must execute against the current real database service.")
    doFirst {
        require(inputs.properties.getValue(enabledEnvironmentVariable) == true) {
            "Set $enabledEnvironmentVariable=true and start the dedicated database test service."
        }
    }
    shouldRunAfter(tasks.named("test"))
}

val workflowPostgresIntegrationTest = registerWorkflowDatabaseIntegrationTest(
    "workflowPostgresIntegrationTest",
    "Runs the FlowWeft Workflow migration and JDBC contract on real PostgreSQL.",
    "**/WorkflowPostgresIntegrationTest.class",
    "FILEWEFT_RUN_POSTGRES_TESTS",
)
val workflowMySqlIntegrationTest = registerWorkflowDatabaseIntegrationTest(
    "workflowMySqlIntegrationTest",
    "Runs the FlowWeft Workflow migration and JDBC contract on real MySQL 8.",
    "**/WorkflowMySQLIntegrationTest.class",
    "FILEWEFT_RUN_MYSQL_TESTS",
)
val workflowKingbaseIntegrationTest = registerWorkflowDatabaseIntegrationTest(
    "workflowKingbaseIntegrationTest",
    "Runs the FlowWeft Workflow migration and JDBC contract on real KingbaseES.",
    "**/WorkflowKingbaseIntegrationTest.class",
    "FILEWEFT_RUN_KINGBASE_TESTS",
)
