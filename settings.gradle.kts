pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "flowweft"

includeBuild("build-logic")

val publicationInventoryFile = file("gradle/publication-inventory.tsv")
require(publicationInventoryFile.isFile && publicationInventoryFile.length() > 0L) {
    "Publication inventory is missing or empty: ${publicationInventoryFile.absolutePath}"
}
val publicationInventoryText = publicationInventoryFile.readText(Charsets.UTF_8)
    .replace("\r\n", "\n")
    .replace('\r', '\n')
val publicationInventoryRawLines = publicationInventoryText.split('\n')
val publicationInventoryLines = if (publicationInventoryRawLines.lastOrNull().isNullOrEmpty()) {
    publicationInventoryRawLines.dropLast(1)
} else {
    publicationInventoryRawLines
}
val publicationInventoryHeader = "artifactId\tartifactKind\tlineage\tjvmBaseline"
require(publicationInventoryLines.firstOrNull() == publicationInventoryHeader) {
    "Publication inventory must start with the exact TSV header '$publicationInventoryHeader'."
}
val publicationModuleNames = publicationInventoryLines.drop(1).mapIndexed { index, line ->
    val lineNumber = index + 2
    val columns = line.split('\t', limit = 5)
    require(columns.size == 4 && columns.all { column -> column.isNotEmpty() && column == column.trim() }) {
        "Publication inventory line $lineNumber must contain exactly four non-padded TSV fields."
    }
    val artifactId = columns[0]
    require(artifactId.length in 3..80 && Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$").matches(artifactId)) {
        "Publication inventory line $lineNumber has an invalid artifact ID '$artifactId'."
    }
    require(columns[1] in setOf("jar", "platform")) {
        "Publication inventory line $lineNumber has unsupported artifact kind '${columns[1]}'."
    }
    require(columns[2] in setOf("legacy-physical", "new-physical")) {
        "Publication inventory line $lineNumber has unsupported lineage '${columns[2]}'."
    }
    val jvmBaseline = columns[3].toIntOrNull()
    require(jvmBaseline != null && jvmBaseline in setOf(8, 17)) {
        "Publication inventory line $lineNumber has unsupported JVM baseline '${columns[3]}'."
    }
    artifactId
}
require(publicationModuleNames.isNotEmpty() && publicationModuleNames.size == publicationModuleNames.distinct().size) {
    "Publication inventory must contain a non-empty unique artifact ID set."
}

include(*publicationModuleNames.map { artifactId -> ":$artifactId" }.toTypedArray())
include(
    ":fileweft-adapter-opentelemetry",
    ":fileweft-sample-host",
    ":fileweft-dev",
)
