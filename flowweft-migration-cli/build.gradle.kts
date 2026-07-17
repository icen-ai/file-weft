import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("fileweft.jvm8-library")
    application
}

application {
    mainClass.set("ai.icen.fw.migration.cli.FlowWeftMigrationCliKt")
}

dependencies {
    implementation(project(":fileweft-persistence"))
    implementation(project(":flowweft-workflow-persistence-jdbc"))
    implementation(project(":flowweft-capacity-persistence-jdbc"))
    implementation(project(":flowweft-reliability-persistence-jdbc"))
    implementation(project(":flowweft-governance-persistence-jdbc"))

    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.mysql.connector.j)
    runtimeOnly(libs.kingbase.jdbc)
    runtimeOnly(libs.flyway.mysql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Jar>("jar") {
    manifest.attributes["Main-Class"] = application.mainClass.get()
}

val integrationTestSourceSet = sourceSets.named("test")
val integrationJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.named<Test>("test") {
    exclude("**/*IntegrationTest.class")
}

fun registerMigrationDatabaseIntegrationTest(
    taskName: String,
    descriptionText: String,
    testPattern: String,
    enabledEnvironmentVariable: String,
): org.gradle.api.tasks.TaskProvider<Test> = tasks.register<Test>(taskName) {
    group = "verification"
    description = descriptionText
    testClassesDirs = integrationTestSourceSet.get().output.classesDirs
    classpath = integrationTestSourceSet.get().runtimeClasspath
    useJUnitPlatform()
    javaLauncher.set(integrationJavaLauncher)
    include(testPattern)
    maxParallelForks = 1
    val enabled = providers.environmentVariable(enabledEnvironmentVariable)
        .map { value -> value == "true" }
        .orElse(false)
    inputs.property(enabledEnvironmentVariable, enabled)
    doNotTrackState("The migration CLI contract must execute against the current real database service.")
    doFirst {
        require(inputs.properties.getValue(enabledEnvironmentVariable) == true) {
            "Set $enabledEnvironmentVariable=true and start the dedicated database test service."
        }
    }
    shouldRunAfter(tasks.named("test"))
}

registerMigrationDatabaseIntegrationTest(
    "flowweftMigrationPostgresIntegrationTest",
    "Runs migrate-and-exit fresh, 0.0.3 upgrade, workflow-only, validate, and failure contracts on PostgreSQL.",
    "**/FlowWeftMigrationPostgresIntegrationTest.class",
    "FILEWEFT_RUN_POSTGRES_TESTS",
)
registerMigrationDatabaseIntegrationTest(
    "flowweftMigrationMySqlIntegrationTest",
    "Runs migrate-and-exit fresh, 0.0.3 upgrade, workflow-only, validate, and failure contracts on MySQL 8.",
    "**/FlowWeftMigrationMySQLIntegrationTest.class",
    "FILEWEFT_RUN_MYSQL_TESTS",
)
registerMigrationDatabaseIntegrationTest(
    "flowweftMigrationKingbaseIntegrationTest",
    "Runs migrate-and-exit fresh, 0.0.3 upgrade, workflow-only, validate, and failure contracts on KingbaseES.",
    "**/FlowWeftMigrationKingbaseIntegrationTest.class",
    "FILEWEFT_RUN_KINGBASE_TESTS",
)
