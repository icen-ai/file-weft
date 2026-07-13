import org.gradle.api.tasks.testing.Test
import org.gradle.api.artifacts.Configuration
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher

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
    runtimeOnly(libs.flyway.mysql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.mysql.connector.j)
    runtimeOnly(libs.kingbase.jdbc)
    testImplementation(platform(libs.junit.bom))
    testImplementation(project(":fileweft-agent"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.postgresql)
    testImplementation(libs.mysql.connector.j)
    testImplementation(libs.kingbase.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
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
val flyway8JavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(8))
}
val flyway11JavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}

fun flywayHostRuntime(
    name: String,
    version: String,
    includePostgreSqlModule: Boolean,
): Configuration {
    val configuration = configurations.create(name) {
        isCanBeConsumed = false
        isCanBeResolved = true
        extendsFrom(configurations.testRuntimeClasspath.get())
        resolutionStrategy.eachDependency {
            if (requested.group == "org.flywaydb" &&
                requested.name in setOf("flyway-core", "flyway-mysql", "flyway-database-postgresql")
            ) {
                useVersion(version)
                because("The compatibility lane must contain exactly one coherent Flyway host version.")
            }
        }
    }
    dependencies.add(configuration.name, "org.flywaydb:flyway-core:$version")
    dependencies.add(configuration.name, "org.flywaydb:flyway-mysql:$version")
    if (includePostgreSqlModule) {
        dependencies.add(configuration.name, "org.flywaydb:flyway-database-postgresql:$version")
    }
    return configuration
}

val flyway8HostRuntime = flywayHostRuntime("flyway8HostRuntime", "8.5.13", false)
val flyway11HostRuntime = flywayHostRuntime("flyway11HostRuntime", "11.7.2", true)

fun registerFlywayHostCompatibilityTest(
    name: String,
    descriptionText: String,
    runtime: Configuration,
    testPattern: String,
    enabledFlag: Provider<Boolean>,
    enabledFlagName: String,
    expectedFlywayVersion: String,
    expectedFlywayModules: Set<String>,
    launcher: Provider<JavaLauncher>,
    additionalTestPatterns: List<String> = emptyList(),
): org.gradle.api.tasks.TaskProvider<Test> = tasks.register<Test>(name) {
    group = "verification"
    description = descriptionText
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().output + sourceSets.main.get().output + runtime
    useJUnitPlatform()
    javaLauncher.set(launcher)
    filter {
        includeTestsMatching(testPattern)
        additionalTestPatterns.forEach(::includeTestsMatching)
    }
    maxParallelForks = 1
    inputs.property(enabledFlagName, enabledFlag)
    doNotTrackState("The Flyway host compatibility test must execute against the current database state.")
    doFirst(
        org.gradle.api.Action<org.gradle.api.Task> {
            require(inputs.properties.getValue(enabledFlagName) == true) {
                "Set $enabledFlagName=true and start the dedicated database test service."
            }
            val flywayArtifacts = runtime.resolvedConfiguration.resolvedArtifacts
                .filter { artifact -> artifact.moduleVersion.id.group == "org.flywaydb" }
            val modules = flywayArtifacts.map { artifact -> artifact.name }.toSet()
            val versions = flywayArtifacts.map { artifact -> artifact.moduleVersion.id.version }.toSet()
            require(modules == expectedFlywayModules && versions == setOf(expectedFlywayVersion)) {
                "Expected Flyway $expectedFlywayVersion modules $expectedFlywayModules, " +
                    "but resolved ${flywayArtifacts.map { artifact -> "${artifact.name}:${artifact.moduleVersion.id.version}" }}"
            }
        },
    )
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
    description = "Runs the dedicated MySQL 8 persistence integration suite exactly once."
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
    description = "Runs the dedicated KingbaseES V8 persistence integration suite exactly once."
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
                "Set FILEWEFT_RUN_KINGBASE_TESTS=true and start the dedicated Kingbase test service."
            }
        },
    )
    shouldRunAfter(tasks.named("test"))
}

val postgresFlyway8CompatibilityTest = registerFlywayHostCompatibilityTest(
    name = "postgresFlyway8CompatibilityTest",
    descriptionText = "Runs a real PostgreSQL migration with the Spring Boot 2 managed Flyway 8 runtime.",
    runtime = flyway8HostRuntime,
    testPattern = "*FlywayMigrationRunnerIntegrationTest.applies schema migrations*",
    enabledFlag = runPostgresIntegration,
    enabledFlagName = "FILEWEFT_RUN_POSTGRES_TESTS",
    expectedFlywayVersion = "8.5.13",
    expectedFlywayModules = setOf("flyway-core", "flyway-mysql"),
    launcher = flyway8JavaLauncher,
).also { task -> task.configure { mustRunAfter(postgresIntegrationTest) } }

val postgresFlyway11CompatibilityTest = registerFlywayHostCompatibilityTest(
    name = "postgresFlyway11CompatibilityTest",
    descriptionText = "Runs a real PostgreSQL migration with the Spring Boot 3 managed Flyway 11 runtime.",
    runtime = flyway11HostRuntime,
    testPattern = "*FlywayMigrationRunnerIntegrationTest.applies schema migrations*",
    enabledFlag = runPostgresIntegration,
    enabledFlagName = "FILEWEFT_RUN_POSTGRES_TESTS",
    expectedFlywayVersion = "11.7.2",
    expectedFlywayModules = setOf("flyway-core", "flyway-mysql", "flyway-database-postgresql"),
    launcher = flyway11JavaLauncher,
).also { task -> task.configure { mustRunAfter(postgresFlyway8CompatibilityTest) } }

val mysqlFlyway8CompatibilityTest = registerFlywayHostCompatibilityTest(
    name = "mysqlFlyway8CompatibilityTest",
    descriptionText = "Runs a real MySQL migration with the Spring Boot 2 managed Flyway 8 runtime.",
    runtime = flyway8HostRuntime,
    testPattern = "*MySQLFlywayMigrationRunnerIntegrationTest.applies all mysql migrations*",
    enabledFlag = runMySqlIntegration,
    enabledFlagName = "FILEWEFT_RUN_MYSQL_TESTS",
    expectedFlywayVersion = "8.5.13",
    expectedFlywayModules = setOf("flyway-core", "flyway-mysql"),
    launcher = flyway8JavaLauncher,
    additionalTestPatterns = listOf(
        "*MySQLFlywayRunnerBoundaryIntegrationTest.recovers when concurrent MySQL history " +
            "changes from absent to empty before baseline*",
    ),
).also { task -> task.configure { mustRunAfter(mysqlIntegrationTest) } }

val mysqlFlyway11CompatibilityTest = registerFlywayHostCompatibilityTest(
    name = "mysqlFlyway11CompatibilityTest",
    descriptionText = "Runs a real MySQL migration with the Spring Boot 3 managed Flyway 11 runtime.",
    runtime = flyway11HostRuntime,
    testPattern = "*MySQLFlywayMigrationRunnerIntegrationTest.applies all mysql migrations*",
    enabledFlag = runMySqlIntegration,
    enabledFlagName = "FILEWEFT_RUN_MYSQL_TESTS",
    expectedFlywayVersion = "11.7.2",
    expectedFlywayModules = setOf("flyway-core", "flyway-mysql", "flyway-database-postgresql"),
    launcher = flyway11JavaLauncher,
    additionalTestPatterns = listOf(
        "*MySQLFlywayRunnerBoundaryIntegrationTest.recovers when concurrent MySQL history " +
            "changes from absent to empty before baseline*",
    ),
).also { task -> task.configure { mustRunAfter(mysqlFlyway8CompatibilityTest) } }

val kingbaseFlyway8CompatibilityTest = registerFlywayHostCompatibilityTest(
    name = "kingbaseFlyway8CompatibilityTest",
    descriptionText = "Runs a real Kingbase migration with the Spring Boot 2 managed Flyway 8 runtime.",
    runtime = flyway8HostRuntime,
    testPattern = "*KingbaseFlywayMigrationRunnerIntegrationTest.applies all kingbase migrations*",
    enabledFlag = runKingbaseIntegration,
    enabledFlagName = "FILEWEFT_RUN_KINGBASE_TESTS",
    expectedFlywayVersion = "8.5.13",
    expectedFlywayModules = setOf("flyway-core", "flyway-mysql"),
    launcher = flyway8JavaLauncher,
).also { task -> task.configure { mustRunAfter(kingbaseIntegrationTest) } }

val kingbaseFlyway11CompatibilityTest = registerFlywayHostCompatibilityTest(
    name = "kingbaseFlyway11CompatibilityTest",
    descriptionText = "Runs a real Kingbase migration with the Spring Boot 3 managed Flyway 11 runtime.",
    runtime = flyway11HostRuntime,
    testPattern = "*KingbaseFlywayMigrationRunnerIntegrationTest.applies all kingbase migrations*",
    enabledFlag = runKingbaseIntegration,
    enabledFlagName = "FILEWEFT_RUN_KINGBASE_TESTS",
    expectedFlywayVersion = "11.7.2",
    expectedFlywayModules = setOf("flyway-core", "flyway-mysql", "flyway-database-postgresql"),
    launcher = flyway11JavaLauncher,
).also { task -> task.configure { mustRunAfter(kingbaseFlyway8CompatibilityTest) } }
