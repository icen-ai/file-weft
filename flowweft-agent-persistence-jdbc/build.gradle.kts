import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("fileweft.jvm8-library")
}

val testSourceSet = sourceSets.named("test")
val integrationJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.named<Test>("test") {
    exclude("**/*IntegrationTest.class")
}

fun registerAgentDatabaseIntegrationTest(
    taskName: String,
    testPattern: String,
    enabledEnvironmentVariable: String,
): org.gradle.api.tasks.TaskProvider<Test> = tasks.register<Test>(taskName) {
    group = "verification"
    description = "Runs the FlowWeft Agent migration and JDBC contract on a dedicated real database."
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
    doNotTrackState("The Agent database contract must execute against the current real database service.")
    doFirst {
        require(inputs.properties.getValue(enabledEnvironmentVariable) == true) {
            "Set $enabledEnvironmentVariable=true and start the dedicated database test service."
        }
    }
    shouldRunAfter(tasks.named("test"))
}

registerAgentDatabaseIntegrationTest(
    "agentPostgresIntegrationTest",
    "**/AgentPostgresIntegrationTest.class",
    "FILEWEFT_RUN_POSTGRES_TESTS",
)
registerAgentDatabaseIntegrationTest(
    "agentMySqlIntegrationTest",
    "**/AgentMySQLIntegrationTest.class",
    "FILEWEFT_RUN_MYSQL_TESTS",
)
registerAgentDatabaseIntegrationTest(
    "agentKingbaseIntegrationTest",
    "**/AgentKingbaseIntegrationTest.class",
    "FILEWEFT_RUN_KINGBASE_TESTS",
)

dependencies {
    api(project(":flowweft-agent-runtime"))

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
