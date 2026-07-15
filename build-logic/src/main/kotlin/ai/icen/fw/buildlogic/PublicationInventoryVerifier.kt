package ai.icen.fw.buildlogic

import java.io.File
import java.security.MessageDigest

data class PublicationInventoryEntry(
    val artifactId: String,
    val artifactKind: String,
    val lineage: String,
    val jvmBaseline: Int,
)

/**
 * Parses and verifies the checked-in physical publication inventory.
 *
 * The TSV is deliberately dependency-free so settings scripts, the main build and independent
 * consumer builds can all read the same artifact list before any project has been configured.
 */
class PublicationInventoryVerifier(
    private val inventoryFile: File,
    private val repositoryRoot: File,
) {

    fun verify(): List<PublicationInventoryEntry> = parse(inventoryFile).also { entries ->
        val legacyEntries = entries.filter { entry -> entry.lineage == LEGACY_PHYSICAL }
        require(legacyEntries.size == EXPECTED_LEGACY_ARTIFACT_COUNT) {
            "Publication inventory must retain exactly $EXPECTED_LEGACY_ARTIFACT_COUNT legacy physical artifacts; " +
                "found ${legacyEntries.size}."
        }
        require(legacyEntries.all { entry -> entry.artifactKind == JAR_KIND }) {
            "Legacy physical artifacts must remain JAR publications."
        }
        val legacyDigest = digestArtifactIds(legacyEntries.map { entry -> entry.artifactId })
        require(legacyDigest == EXPECTED_LEGACY_ARTIFACT_IDS_SHA256) {
            "Publication inventory changed the frozen legacy 0.0.x artifact set; " +
                "expected digest $EXPECTED_LEGACY_ARTIFACT_IDS_SHA256, actual $legacyDigest."
        }
        require(legacyEntries.all { entry -> entry.artifactId.startsWith("fileweft-") }) {
            "Legacy physical artifacts must retain their fileweft-* artifact IDs."
        }

        val newEntries = entries.filter { entry -> entry.lineage == NEW_PHYSICAL }
        require(newEntries.all { entry -> entry.artifactId.startsWith("flowweft-") }) {
            "New physical artifacts must use flowweft-* artifact IDs."
        }

        entries.forEach { entry ->
            val moduleDirectory = repositoryRoot.resolve(entry.artifactId)
            require(moduleDirectory.isDirectory) {
                "Publication inventory module directory is missing: ${moduleDirectory.absolutePath}"
            }
            val buildFile = moduleDirectory.resolve("build.gradle.kts")
            require(buildFile.isFile && buildFile.length() > 0L) {
                "Publication inventory module has no non-empty build.gradle.kts: ${buildFile.absolutePath}"
            }
            if (entry.artifactKind == JAR_KIND) {
                val expectedConvention = "fileweft.jvm${entry.jvmBaseline}-library"
                require(buildFile.readText(Charsets.UTF_8).contains("id(\"$expectedConvention\")")) {
                    "Publication inventory JVM baseline does not match ${entry.artifactId}; " +
                        "expected convention plugin $expectedConvention."
                }
            }
        }
    }

    companion object {
        const val HEADER = "artifactId\tartifactKind\tlineage\tjvmBaseline"
        const val JAR_KIND = "jar"
        const val PLATFORM_KIND = "platform"
        const val LEGACY_PHYSICAL = "legacy-physical"
        const val NEW_PHYSICAL = "new-physical"

        private const val EXPECTED_LEGACY_ARTIFACT_COUNT = 19
        private const val EXPECTED_LEGACY_ARTIFACT_IDS_SHA256 =
            "dab8398fdd73e2921faa07b99f2110e7a84f5bc729e7fb1fa05226b3aa2749bc"
        private val artifactIdPattern = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
        private val allowedArtifactKinds = setOf(JAR_KIND, PLATFORM_KIND)
        private val allowedLineages = setOf(LEGACY_PHYSICAL, NEW_PHYSICAL)
        private val allowedJvmBaselines = setOf(8, 17)

        @JvmStatic
        fun parse(inventoryFile: File): List<PublicationInventoryEntry> {
            require(inventoryFile.isFile && inventoryFile.length() > 0L) {
                "Publication inventory is missing or empty: ${inventoryFile.absolutePath}"
            }
            return parse(inventoryFile.readText(Charsets.UTF_8), inventoryFile.path)
        }

        @JvmStatic
        fun parse(text: String, sourceName: String = "publication inventory"): List<PublicationInventoryEntry> {
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            val rawLines = normalized.split('\n')
            val lines = if (rawLines.lastOrNull().isNullOrEmpty()) rawLines.dropLast(1) else rawLines
            require(lines.isNotEmpty() && lines.first() == HEADER) {
                "$sourceName must start with the exact TSV header '$HEADER'."
            }
            require(lines.size > 1) { "$sourceName contains no publication entries." }

            val entries = lines.drop(1).mapIndexed { index, line ->
                val lineNumber = index + 2
                require(line.isNotBlank()) { "$sourceName contains a blank row at line $lineNumber." }
                val columns = line.split('\t', limit = 5)
                require(columns.size == 4) {
                    "$sourceName line $lineNumber must contain exactly four tab-separated columns."
                }
                require(columns.all { column -> column.isNotEmpty() && column == column.trim() }) {
                    "$sourceName line $lineNumber contains an empty or padded field."
                }
                val artifactId = columns[0]
                val artifactKind = columns[1]
                val lineage = columns[2]
                val jvmBaseline = columns[3].toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "$sourceName line $lineNumber has a non-numeric JVM baseline '${columns[3]}'.",
                    )

                require(artifactId.length in 3..80 && artifactIdPattern.matches(artifactId)) {
                    "$sourceName line $lineNumber has an invalid or unbounded artifact ID '$artifactId'."
                }
                require(artifactKind in allowedArtifactKinds) {
                    "$sourceName line $lineNumber has unsupported artifact kind '$artifactKind'; " +
                        "allowed=$allowedArtifactKinds."
                }
                require(lineage in allowedLineages) {
                    "$sourceName line $lineNumber has unsupported lineage '$lineage'; allowed=$allowedLineages."
                }
                require(jvmBaseline in allowedJvmBaselines) {
                    "$sourceName line $lineNumber has unsupported JVM baseline '$jvmBaseline'; " +
                        "allowed=$allowedJvmBaselines."
                }
                PublicationInventoryEntry(artifactId, artifactKind, lineage, jvmBaseline)
            }

            val duplicates = entries.groupingBy { entry -> entry.artifactId }
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys
            require(duplicates.isEmpty()) { "$sourceName contains duplicate artifact IDs: ${duplicates.sorted()}" }
            return entries
        }

        private fun digestArtifactIds(artifactIds: List<String>): String =
            MessageDigest.getInstance("SHA-256")
                .digest(artifactIds.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
    }
}
