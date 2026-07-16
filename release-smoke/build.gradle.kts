import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.JavaVersion
import org.gradle.api.attributes.Bundling
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.21" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

data class ReleasePublicationInventoryEntry(
    val artifactId: String,
    val artifactKind: String,
    val lineage: String,
    val jvmBaseline: Int,
)

fun readPublicationInventory(inventoryFile: File): List<ReleasePublicationInventoryEntry> {
    require(inventoryFile.isFile && inventoryFile.length() > 0L) {
        "Publication inventory is missing or empty: ${inventoryFile.absolutePath}"
    }
    val normalized = inventoryFile.readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    val rawLines = normalized.split('\n')
    val lines = if (rawLines.lastOrNull().isNullOrEmpty()) rawLines.dropLast(1) else rawLines
    val header = "artifactId\tartifactKind\tlineage\tjvmBaseline"
    require(lines.firstOrNull() == header) {
        "Publication inventory must start with the exact TSV header '$header'."
    }
    val entries = lines.drop(1).mapIndexed { index, line ->
        val lineNumber = index + 2
        val columns = line.split('\t', limit = 5)
        require(columns.size == 4 && columns.all { column -> column.isNotEmpty() && column == column.trim() }) {
            "Publication inventory line $lineNumber must contain exactly four non-padded TSV fields."
        }
        val artifactId = columns[0]
        val jvmBaseline = columns[3].toIntOrNull()
        require(artifactId.length in 3..80 && Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$").matches(artifactId)) {
            "Publication inventory line $lineNumber has an invalid artifact ID '$artifactId'."
        }
        require(columns[1] in setOf("jar", "platform")) {
            "Publication inventory line $lineNumber has unsupported artifact kind '${columns[1]}'."
        }
        require(columns[2] in setOf("legacy-physical", "new-physical")) {
            "Publication inventory line $lineNumber has unsupported lineage '${columns[2]}'."
        }
        require(jvmBaseline != null && jvmBaseline in setOf(8, 17)) {
            "Publication inventory line $lineNumber has unsupported JVM baseline '${columns[3]}'."
        }
        ReleasePublicationInventoryEntry(artifactId, columns[1], columns[2], jvmBaseline)
    }
    require(entries.isNotEmpty() && entries.size == entries.map { entry -> entry.artifactId }.distinct().size) {
        "Publication inventory must contain a non-empty unique artifact ID set."
    }
    return entries
}

version = providers.gradleProperty("fileweftVersion").orNull
    ?: throw GradleException("-PfileweftVersion is required for release consumer smoke testing.")
val flowWeftRepositoryUrl = providers.gradleProperty("fileweftRepositoryUrl")
    .orElse(rootDir.resolve("../build/repository").toURI().toString())

val springBoot2Version = "2.7.18"
val springBoot3Version = "3.5.16"
val junitVersion = "5.11.4"
val kotlinVersion = "2.1.21"
val slf4jBoot2Version = "1.7.36"
val flywayBoot2Version = "8.5.13"
val flywayBoot3Version = "11.7.2"
val tomcatBoot3Version = "10.1.57"
val logbackBoot3Version = "1.5.38"

val publicationInventory = readPublicationInventory(rootDir.resolve("../gradle/publication-inventory.tsv"))
val publishedJarInventory = publicationInventory.filter { entry -> entry.artifactKind == "jar" }
val expectedFlowWeftModules = publishedJarInventory.map { entry -> entry.artifactId }.toSet()
val releaseInventory = configurations.create("releaseInventory") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
val boot3UnmanagedRuntime = configurations.create("boot3UnmanagedRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val migrationCliRuntime = configurations.create("migrationCliRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    expectedFlowWeftModules.forEach { moduleName ->
        add(releaseInventory.name, "ai.icen:$moduleName:${project.version}")
    }
    add(boot3UnmanagedRuntime.name, "ai.icen:fileweft-spring-boot3-starter:${project.version}")
    add(boot3UnmanagedRuntime.name, "ai.icen:fileweft-web-spring-boot3-starter:${project.version}")
    add(migrationCliRuntime.name, "ai.icen:flowweft-migration-cli:${project.version}")
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "ai.icen.release.smoke"
    version = rootProject.version

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

fun Project.targetJvm(javaVersion: JavaVersion, kotlinTarget: JvmTarget, release: Int) {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(release)
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(kotlinTarget)
        compilerOptions.javaParameters.set(true)
    }
}

project(":boot2-consumer") {
    targetJvm(JavaVersion.VERSION_1_8, JvmTarget.JVM_1_8, 8)
    extra["kotlin.version"] = kotlinVersion
    apply(plugin = "io.spring.dependency-management")
    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:$springBoot2Version")
        }
    }
    dependencies {
        add("implementation", "org.springframework.boot:spring-boot-starter-jdbc")
        add("implementation", "ai.icen:fileweft-spring-boot2-starter:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-web-spring-boot2-starter:${rootProject.version}")
        add("testImplementation", platform("org.junit:junit-bom:$junitVersion"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        add("testRuntimeOnly", "com.h2database:h2")
    }

    val runtimeClasspath = configurations.named("runtimeClasspath")
    val verifyBoot2ManagedVersions = tasks.register("verifyBoot2ManagedVersions") {
        group = "verification"
        description = "Asserts the Boot 2 host resolves the supported Kotlin and SLF4J runtime versions."
        inputs.files(runtimeClasspath)

        doLast {
            val resolvedVersions = runtimeClasspath.get().incoming.resolutionResult.allComponents
                .mapNotNull { component ->
                    component.moduleVersion?.let { module ->
                        "${module.group}:${module.name}" to module.version
                    }
                }
                .toMap()
            require(resolvedVersions["org.jetbrains.kotlin:kotlin-stdlib"] == kotlinVersion) {
                "Boot 2 must resolve kotlin-stdlib $kotlinVersion, but resolved " +
                    resolvedVersions["org.jetbrains.kotlin:kotlin-stdlib"]
            }
            require(resolvedVersions["org.slf4j:slf4j-api"] == slf4jBoot2Version) {
                "Boot 2 must resolve slf4j-api $slf4jBoot2Version, but resolved " +
                    resolvedVersions["org.slf4j:slf4j-api"]
            }
            val flyway = resolvedVersions.filterKeys { coordinate -> coordinate.startsWith("org.flywaydb:") }
            require(
                flyway == mapOf(
                    "org.flywaydb:flyway-core" to flywayBoot2Version,
                    "org.flywaydb:flyway-mysql" to flywayBoot2Version,
                ),
            ) {
                "Boot 2 must resolve one coherent Flyway $flywayBoot2Version runtime, but resolved $flyway"
            }
        }
    }
    tasks.named("check") {
        dependsOn(verifyBoot2ManagedVersions)
    }
}

project(":boot3-consumer") {
    targetJvm(JavaVersion.VERSION_17, JvmTarget.JVM_17, 17)
    dependencies {
        add("implementation", platform("org.springframework.boot:spring-boot-dependencies:$springBoot3Version"))
        add("implementation", "org.springframework.boot:spring-boot-starter-jdbc")
        add("implementation", "ai.icen:fileweft-spring-boot3-starter:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-web-spring-boot3-starter:${rootProject.version}")
        add("testImplementation", platform("org.junit:junit-bom:$junitVersion"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        add("testRuntimeOnly", "com.h2database:h2")

    }

    val runtimeClasspath = configurations.named("runtimeClasspath")
    val verifyBoot3ManagedVersions = tasks.register("verifyBoot3ManagedVersions") {
        group = "verification"
        description = "Asserts the Boot 3 host resolves one coherent Flyway 11 runtime."
        inputs.files(runtimeClasspath)
        doLast {
            val resolvedVersions = runtimeClasspath.get().incoming.resolutionResult.allComponents
                .mapNotNull { component -> component.moduleVersion }
                .associate { module -> "${module.group}:${module.name}" to module.version }
            val flyway = resolvedVersions
                .filterKeys { coordinate -> coordinate.startsWith("org.flywaydb:") }
                .mapKeys { (coordinate, _) -> coordinate.substringAfter(':') }
            require(
                flyway == mapOf(
                    "flyway-core" to flywayBoot3Version,
                    "flyway-mysql" to flywayBoot3Version,
                    "flyway-database-postgresql" to flywayBoot3Version,
                ),
            ) {
                "Boot 3 must resolve one coherent Flyway $flywayBoot3Version runtime, but resolved $flyway"
            }
            setOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket").forEach { module ->
                require(resolvedVersions["org.apache.tomcat.embed:$module"] == tomcatBoot3Version) {
                    "Boot 3 must resolve $module $tomcatBoot3Version, but resolved " +
                        resolvedVersions["org.apache.tomcat.embed:$module"]
                }
            }
            setOf("logback-classic", "logback-core").forEach { module ->
                require(resolvedVersions["ch.qos.logback:$module"] == logbackBoot3Version) {
                    "Boot 3 must resolve $module $logbackBoot3Version, but resolved " +
                        resolvedVersions["ch.qos.logback:$module"]
                }
            }
        }
    }
    tasks.named("check") {
        dependsOn(verifyBoot3ManagedVersions)
    }
}

project(":library-consumer") {
    targetJvm(JavaVersion.VERSION_1_8, JvmTarget.JVM_1_8, 8)
    dependencies {
        add("implementation", "ai.icen:fileweft-spi:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-retrieval-api:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-retrieval-spi:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-retrieval-runtime:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-agent-api:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-agent-runtime:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-workflow-api:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-workflow-spi:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-workflow-domain:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-workflow-runtime:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-workflow-persistence-jdbc:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-migration-cli:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-adapter-dify:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-adapter-oss:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-agent:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-persistence:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-adapter-micrometer:${rootProject.version}")
    }
}

val verifyReleaseInventory = tasks.register("verifyReleaseInventory") {
    group = "verification"
    description = "Resolves the exact public FlowWeft module inventory from the configured Maven repository."
    inputs.property("expectedFlowWeftModules", expectedFlowWeftModules.sorted())
    inputs.files(releaseInventory)

    doLast {
        val resolvedFlowWeftModules = releaseInventory.resolvedConfiguration.resolvedArtifacts
            .map { artifact -> artifact.moduleVersion.id }
            .filter { component -> component.group == "ai.icen" && component.version == project.version.toString() }
            .map { component -> component.name }
            .toSet()
        require(resolvedFlowWeftModules == expectedFlowWeftModules) {
            "Resolved FlowWeft inventory differs from the release contract; " +
                "missing=${expectedFlowWeftModules - resolvedFlowWeftModules}, " +
                "unexpected=${resolvedFlowWeftModules - expectedFlowWeftModules}."
        }
    }
}

val verifyUnmanagedBoot3FlywayRuntime = tasks.register("verifyUnmanagedBoot3FlywayRuntime") {
    group = "verification"
    description = "Proves the published Boot 3 starter keeps Flyway coherent even without a host BOM."
    inputs.files(boot3UnmanagedRuntime)
    doLast {
        val resolvedVersions = boot3UnmanagedRuntime.incoming.resolutionResult.allComponents
            .mapNotNull { component -> component.moduleVersion }
            .associate { module -> "${module.group}:${module.name}" to module.version }
        val flyway = resolvedVersions
            .filterKeys { coordinate -> coordinate.startsWith("org.flywaydb:") }
            .mapKeys { (coordinate, _) -> coordinate.substringAfter(':') }
        require(
            flyway == mapOf(
                "flyway-core" to flywayBoot3Version,
                "flyway-mysql" to flywayBoot3Version,
                "flyway-database-postgresql" to flywayBoot3Version,
            ),
        ) {
            "Unmanaged Boot 3 consumer must resolve one coherent Flyway $flywayBoot3Version runtime, but resolved $flyway"
        }
        setOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket").forEach { module ->
            require(resolvedVersions["org.apache.tomcat.embed:$module"] == tomcatBoot3Version) {
                "Unmanaged Boot 3 consumer must resolve $module $tomcatBoot3Version, but resolved " +
                    resolvedVersions["org.apache.tomcat.embed:$module"]
            }
        }
        setOf("logback-classic", "logback-core").forEach { module ->
            require(resolvedVersions["ch.qos.logback:$module"] == logbackBoot3Version) {
                "Unmanaged Boot 3 consumer must resolve $module $logbackBoot3Version, but resolved " +
                    resolvedVersions["ch.qos.logback:$module"]
            }
        }
        require("org.apache.tomcat:tomcat-annotations-api" !in resolvedVersions) {
            "The patched direct Tomcat dependencies must preserve Spring Boot's tomcat-annotations-api exclusion."
        }
    }
}

val verifyMigrationCliRuntime = tasks.register("verifyMigrationCliRuntime") {
    group = "verification"
    description = "Verifies the published migration CLI resolves the patched JDBC runtime without optional OCI SDKs."
    inputs.files(migrationCliRuntime)
    doLast {
        val resolvedVersions = migrationCliRuntime.incoming.resolutionResult.allComponents
            .mapNotNull { component -> component.moduleVersion }
            .associate { module -> "${module.group}:${module.name}" to module.version }
        val expected = mapOf(
            "org.postgresql:postgresql" to "42.7.11",
            "com.mysql:mysql-connector-j" to "9.7.0",
            "com.google.protobuf:protobuf-java" to "4.31.1",
        )
        expected.forEach { (coordinate, version) ->
            require(resolvedVersions[coordinate] == version) {
                "Migration CLI must resolve $coordinate:$version, but resolved ${resolvedVersions[coordinate]}."
            }
        }
        require(resolvedVersions.keys.none { coordinate -> coordinate.startsWith("com.oracle.oci.sdk:") }) {
            "Optional MySQL OCI SDK dependencies must not enter the migration CLI runtime: " +
                resolvedVersions.keys.filter { coordinate -> coordinate.startsWith("com.oracle.oci.sdk:") }
        }
    }
}

val verifyGradleModuleMetadataConsumer = tasks.register<GradleBuild>("verifyGradleModuleMetadataConsumer") {
    group = "verification"
    description = "Cold-resolves FlowWeft through Gradle module metadata and verifies API/runtime/source variants."
    dir = file("gradle-module-consumer")
    tasks = listOf("verifyGradleModuleMetadata")
    startParameter.projectProperties = mapOf(
        "fileweftVersion" to project.version.toString(),
        "fileweftRepositoryUrl" to flowWeftRepositoryUrl.get(),
        "publicationInventoryEntries" to publicationInventory
            .sortedBy { entry -> entry.artifactId }
            .joinToString(",") { entry ->
                "${entry.artifactId}|${entry.artifactKind}|${entry.lineage}|${entry.jvmBaseline}"
            },
    )
}

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val mavenExecutable = providers.provider {
    val executableNames = if (isWindows) listOf("mvn.cmd", "mvn.bat", "mvn.exe") else listOf("mvn")
    val pathDirectories = System.getenv("PATH")
        .orEmpty()
        .split(File.pathSeparatorChar)
        .filter { path -> path.isNotBlank() }
        .map(::File)
    val mavenHomeDirectories = listOfNotNull(
        System.getenv("MAVEN_HOME"),
        System.getenv("M2_HOME"),
    ).map { home -> File(home, "bin") }
    (pathDirectories + mavenHomeDirectories)
        .asSequence()
        .flatMap { directory -> executableNames.asSequence().map(directory::resolve) }
        .firstOrNull { candidate -> candidate.isFile }
        ?: throw GradleException(
            "Maven CLI is required for the independent POM consumer gate. " +
                "Install the CI-pinned Maven distribution and expose mvn on PATH; " +
                "this release check never downloads a Maven runtime.",
        )
}
val mavenConsumerBuildDirectory = layout.buildDirectory.dir("maven-pom-consumer/target")
val mavenConsumerLocalRepository = layout.buildDirectory.dir("maven-pom-consumer/repository")
val verifyMavenPomConsumer = tasks.register<Exec>("verifyMavenPomConsumer") {
    group = "verification"
    description = "Test-compiles a Java 8 consumer with Maven CLI using only published FlowWeft POM dependency metadata."
    workingDir(file("maven-consumer"))
    inputs.files(fileTree("maven-consumer") { include("pom.xml", "src/**/*.java") })
        .withPropertyName("mavenConsumerSources")
    inputs.property("fileweftVersion", project.version.toString())
    inputs.property("fileweftRepositoryUrl", flowWeftRepositoryUrl)

    doFirst {
        project.delete(mavenConsumerBuildDirectory, mavenConsumerLocalRepository)
        val arguments = listOf(
            "-B",
            "-ntp",
            "-U",
            "-Dstyle.color=never",
            "-Dmaven.repo.local=${mavenConsumerLocalRepository.get().asFile.absolutePath}",
            "-Dflowweft.version=${project.version}",
            "-Dflowweft.repository.url=${flowWeftRepositoryUrl.get()}",
            "-Dflowweft.maven.build.directory=${mavenConsumerBuildDirectory.get().asFile.absolutePath}",
            "test-compile",
        )
        val executable = mavenExecutable.get().absolutePath
        if (isWindows) {
            commandLine(listOf(System.getenv("ComSpec") ?: "cmd.exe", "/d", "/c", executable) + arguments)
        } else {
            commandLine(listOf(executable) + arguments)
        }
        val existingMavenOptions = System.getenv("MAVEN_OPTS").orEmpty().trim()
        environment("MAVEN_OPTS", listOf(existingMavenOptions, "-Dfile.encoding=UTF-8").filter(String::isNotEmpty).joinToString(" "))
    }
    doLast {
        val compiledMainConsumer = mavenConsumerBuildDirectory.get().asFile
            .resolve("classes/ai/icen/fw/release/smoke/maven/MavenPomJava8Consumer.class")
        require(compiledMainConsumer.isFile && compiledMainConsumer.length() > 0L) {
            "Maven POM consumer did not produce the expected Java 8 main class: ${compiledMainConsumer.absolutePath}"
        }
        val compiledTestConsumer = mavenConsumerBuildDirectory.get().asFile
            .resolve("test-classes/ai/icen/fw/release/smoke/maven/MavenPublishedTestKitJava8Consumer.class")
        require(compiledTestConsumer.isFile && compiledTestConsumer.length() > 0L) {
            "Maven POM consumer did not produce the expected Java 8 test class: ${compiledTestConsumer.absolutePath}"
        }
    }
}

val mavenBoot3BuildDirectory = layout.buildDirectory.dir("maven-boot3-consumer/target")
val mavenBoot3LocalRepository = layout.buildDirectory.dir("maven-boot3-consumer/repository")
val verifyMavenBoot3Consumer = tasks.register<Exec>("verifyMavenBoot3Consumer") {
    group = "verification"
    description = "Compiles a JDK 17 Maven Boot 3 consumer and verifies its POM-only patched runtime graph."
    workingDir(file("maven-boot3-consumer"))
    inputs.files(fileTree("maven-boot3-consumer") { include("pom.xml", "src/**/*.java") })
        .withPropertyName("mavenBoot3ConsumerSources")
    inputs.property("fileweftVersion", project.version.toString())
    inputs.property("fileweftRepositoryUrl", flowWeftRepositoryUrl)

    doFirst {
        project.delete(mavenBoot3BuildDirectory, mavenBoot3LocalRepository)
        val arguments = listOf(
            "-B",
            "-ntp",
            "-U",
            "-Dstyle.color=never",
            "-Dmaven.repo.local=${mavenBoot3LocalRepository.get().asFile.absolutePath}",
            "-Dflowweft.version=${project.version}",
            "-Dflowweft.repository.url=${flowWeftRepositoryUrl.get()}",
            "-Dflowweft.maven.build.directory=${mavenBoot3BuildDirectory.get().asFile.absolutePath}",
            "compile",
        )
        val executable = mavenExecutable.get().absolutePath
        if (isWindows) {
            commandLine(listOf(System.getenv("ComSpec") ?: "cmd.exe", "/d", "/c", executable) + arguments)
        } else {
            commandLine(listOf(executable) + arguments)
        }
        val existingMavenOptions = System.getenv("MAVEN_OPTS").orEmpty().trim()
        environment(
            "MAVEN_OPTS",
            listOf(existingMavenOptions, "-Dfile.encoding=UTF-8").filter(String::isNotEmpty).joinToString(" "),
        )
    }
    doLast {
        val compiledConsumer = mavenBoot3BuildDirectory.get().asFile
            .resolve("classes/ai/icen/fw/release/smoke/maven/MavenBoot3Consumer.class")
        require(compiledConsumer.isFile && compiledConsumer.length() > 0L) {
            "Maven Boot 3 consumer did not produce the expected JDK 17 class: ${compiledConsumer.absolutePath}"
        }
        val dependencyTreeFile = mavenBoot3BuildDirectory.get().asFile.resolve("dependency-tree.txt")
        require(dependencyTreeFile.isFile && dependencyTreeFile.length() > 0L) {
            "Maven Boot 3 consumer did not record its runtime dependency tree."
        }
        val dependencyTree = dependencyTreeFile.readText(Charsets.UTF_8)
        setOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket").forEach { module ->
            require("org.apache.tomcat.embed:$module:jar:$tomcatBoot3Version" in dependencyTree) {
                "Maven Boot 3 consumer must resolve $module $tomcatBoot3Version."
            }
        }
        setOf("logback-classic", "logback-core").forEach { module ->
            require("ch.qos.logback:$module:jar:$logbackBoot3Version" in dependencyTree) {
                "Maven Boot 3 consumer must resolve $module $logbackBoot3Version."
            }
        }
        require("org.apache.tomcat:tomcat-annotations-api" !in dependencyTree) {
            "Maven Boot 3 consumer must preserve Spring Boot's tomcat-annotations-api exclusion."
        }
    }
}

tasks.register("releaseSmoke") {
    group = "verification"
    description = "Verifies POM and Gradle metadata consumers, the release inventory, and Boot 2/3 startup."
    dependsOn(verifyReleaseInventory)
    dependsOn(verifyUnmanagedBoot3FlywayRuntime)
    dependsOn(verifyMigrationCliRuntime)
    dependsOn(verifyGradleModuleMetadataConsumer)
    dependsOn(verifyMavenPomConsumer)
    dependsOn(verifyMavenBoot3Consumer)
    dependsOn(subprojects.map { consumerProject -> consumerProject.tasks.named("build") })
}
