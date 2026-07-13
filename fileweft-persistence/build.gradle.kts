import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

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

val runPostgresIntegration = providers.environmentVariable("FILEWEFT_RUN_POSTGRES_TESTS")
    .map { value -> value == "true" }
    .orElse(false)
val runMySqlIntegration = providers.environmentVariable("FILEWEFT_RUN_MYSQL_TESTS")
    .map { value -> value == "true" }
    .orElse(false)
val runKingbaseIntegration = providers.environmentVariable("FILEWEFT_RUN_KINGBASE_TESTS")
    .map { value -> value == "true" }
    .orElse(false)
val testSourceSet = sourceSets.named("test")
val integrationJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

val postgresIntegrationTest = tasks.register<Test>("postgresIntegrationTest") {
    group = "verification"
    description = "Runs the PostgreSQL persistence integration suite exactly once outside the JVM matrix."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform()
    javaLauncher.set(integrationJavaLauncher)
    include("**/*IntegrationTest.class")
    exclude("**/*MySQL*IntegrationTest.class", "**/*Kingbase*IntegrationTest.class")
    maxParallelForks = 1
    inputs.property("fileWeftRunPostgresTests", runPostgresIntegration)
    doNotTrackState("The PostgreSQL integration suite must execute against the current database state.")
    doFirst(
        org.gradle.api.Action<org.gradle.api.Task> {
            require(inputs.properties.getValue("fileWeftRunPostgresTests") == true) {
                "Set FILEWEFT_RUN_POSTGRES_TESTS=true and start the dedicated PostgreSQL test service."
            }
        },
    )
    shouldRunAfter(tasks.named("test"))
}

val mysqlIntegrationTest = tasks.register<Test>("mysqlIntegrationTest") {
    group = "verification"
    description = "Runs the optional MySQL persistence integration suite exactly once."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform()
    javaLauncher.set(integrationJavaLauncher)
    include("**/*MySQL*IntegrationTest.class")
    maxParallelForks = 1
    inputs.property("fileWeftRunMySqlTests", runMySqlIntegration)
    doNotTrackState("The MySQL integration suite must execute against the current database state.")
    doFirst(
        org.gradle.api.Action<org.gradle.api.Task> {
            require(inputs.properties.getValue("fileWeftRunMySqlTests") == true) {
                "Set FILEWEFT_RUN_MYSQL_TESTS=true and start the dedicated MySQL test service."
            }
        },
    )
    shouldRunAfter(tasks.named("test"))
}

val kingbaseIntegrationTest = tasks.register<Test>("kingbaseIntegrationTest") {
    group = "verification"
    description = "Runs the optional Kingbase persistence integration suite exactly once."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform()
    javaLauncher.set(integrationJavaLauncher)
    include("**/*Kingbase*IntegrationTest.class")
    maxParallelForks = 1
    inputs.property("fileWeftRunKingbaseTests", runKingbaseIntegration)
    doNotTrackState("The Kingbase integration suite must execute against the current database state.")
    doFirst(
        org.gradle.api.Action<org.gradle.api.Task> {
            require(inputs.properties.getValue("fileWeftRunKingbaseTests") == true) {
                "Set FILEWEFT_RUN_KINGBASE_TESTS=true and provide the Kingbase JDBC driver and dedicated test service."
            }
        },
    )
    shouldRunAfter(tasks.named("test"))
}
