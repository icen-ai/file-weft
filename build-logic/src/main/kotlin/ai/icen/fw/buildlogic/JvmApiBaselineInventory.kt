package ai.icen.fw.buildlogic

import java.io.File
import java.nio.charset.StandardCharsets

data class JvmApiBaselineEntry(
    val version: String,
    val artifactId: String,
    val lineage: String,
    val sourceRef: String,
    val sourceCommit: String?,
    val jarSha256: String?,
    val apiSha256: String?,
    val exportsSha256: String?,
    val state: JvmApiBaselineState,
)

enum class JvmApiBaselineState {
    PENDING,
    READY,
}

/**
 * Strict, dependency-free parser for the checked-in API baseline provenance inventory.
 *
 * A READY row is a trust assertion: it names the immutable release source and exact binary JAR
 * digest used to produce a reviewed `.api` file. PENDING rows are deliberately accepted by the
 * parser but rejected by verification/import tasks, so an unreviewed baseline can never turn a
 * release green merely because a filename exists.
 */
object JvmApiBaselineInventory {
    const val HEADER =
        "version\tartifactId\tlineage\tsourceRef\tsourceCommit\tjarSha256\tapiSha256\texportsSha256\tstate"
    const val NO_EXPORTS = "NONE"
    const val NEW_BASELINE_VERSION = "1.0.0"
    val LEGACY_BASELINE_VERSIONS: List<String> = listOf("0.0.1", "0.0.2", "0.0.3")

    private val versionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    private val artifactPattern = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
    private val sourceRefPattern = Regex("^v[0-9A-Za-z][0-9A-Za-z._-]{0,79}$")
    private val commitPattern = Regex("^[0-9a-f]{40}$")
    private val digestPattern = Regex("^[0-9a-f]{64}$")

    @JvmStatic
    fun parse(file: File): List<JvmApiBaselineEntry> {
        require(file.isFile && file.length() > 0L) {
            "JVM API baseline inventory is missing or empty: ${file.absolutePath}"
        }
        return parse(file.readText(StandardCharsets.UTF_8), file.path)
    }

    @JvmStatic
    fun parse(text: String, sourceName: String = "JVM API baseline inventory"): List<JvmApiBaselineEntry> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val rawLines = normalized.split('\n')
        val lines = if (rawLines.lastOrNull().isNullOrEmpty()) rawLines.dropLast(1) else rawLines
        require(lines.firstOrNull() == HEADER) {
            "$sourceName must start with the exact TSV header '$HEADER'."
        }
        require(lines.size > 1) { "$sourceName contains no baseline entries." }

        val entries = lines.drop(1).mapIndexed { index, line ->
            val lineNumber = index + 2
            require(line.isNotBlank()) { "$sourceName contains a blank row at line $lineNumber." }
            val columns = line.split('\t', limit = 10)
            require(columns.size == 9) {
                "$sourceName line $lineNumber must contain exactly nine tab-separated columns."
            }
            require(columns.all { column -> column.isNotEmpty() && column == column.trim() }) {
                "$sourceName line $lineNumber contains an empty or padded field."
            }

            val version = columns[0]
            val artifactId = columns[1]
            val lineage = columns[2]
            val sourceRef = columns[3]
            val sourceCommit = columns[4].takeUnless { value -> value == PENDING }
            val jarSha256 = columns[5].takeUnless { value -> value == PENDING }
            val apiSha256 = columns[6].takeUnless { value -> value == PENDING }
            val exportsSha256 = columns[7].takeUnless { value -> value == PENDING }
            val state = when (columns[8]) {
                "pending" -> JvmApiBaselineState.PENDING
                "ready" -> JvmApiBaselineState.READY
                else -> throw IllegalArgumentException(
                    "$sourceName line $lineNumber has unsupported state '${columns[8]}'.",
                )
            }

            require(versionPattern.matches(version)) {
                "$sourceName line $lineNumber has invalid semantic version '$version'."
            }
            require(artifactPattern.matches(artifactId) && artifactId.length <= 80) {
                "$sourceName line $lineNumber has invalid artifact ID '$artifactId'."
            }
            require(
                lineage == PublicationInventoryVerifier.LEGACY_PHYSICAL ||
                    lineage == PublicationInventoryVerifier.NEW_PHYSICAL,
            ) {
                "$sourceName line $lineNumber has unsupported lineage '$lineage'."
            }
            require(sourceRefPattern.matches(sourceRef)) {
                "$sourceName line $lineNumber has invalid or unbounded source ref '$sourceRef'."
            }
            require(sourceCommit == null || commitPattern.matches(sourceCommit)) {
                "$sourceName line $lineNumber has invalid source commit '${columns[4]}'."
            }
            require(jarSha256 == null || digestPattern.matches(jarSha256)) {
                "$sourceName line $lineNumber has invalid JAR SHA-256 '${columns[5]}'."
            }
            require(apiSha256 == null || digestPattern.matches(apiSha256)) {
                "$sourceName line $lineNumber has invalid API SHA-256 '${columns[6]}'."
            }
            require(
                exportsSha256 == null || exportsSha256 == NO_EXPORTS || digestPattern.matches(exportsSha256),
            ) {
                "$sourceName line $lineNumber has invalid exports SHA-256 '${columns[7]}'."
            }
            if (lineage == PublicationInventoryVerifier.LEGACY_PHYSICAL) {
                require(exportsSha256 == NO_EXPORTS) {
                    "$sourceName line $lineNumber must use $NO_EXPORTS for a legacy artifact export manifest."
                }
            } else {
                require(exportsSha256 != NO_EXPORTS) {
                    "$sourceName line $lineNumber must bind a new artifact export manifest."
                }
            }
            if (state == JvmApiBaselineState.READY) {
                require(sourceCommit != null && jarSha256 != null && apiSha256 != null) {
                    "$sourceName line $lineNumber is ready but lacks an exact commit, JAR SHA-256, or API SHA-256."
                }
                if (lineage == PublicationInventoryVerifier.NEW_PHYSICAL) {
                    require(exportsSha256 != null && digestPattern.matches(exportsSha256)) {
                        "$sourceName line $lineNumber is ready but lacks an exact exports SHA-256."
                    }
                }
            }

            JvmApiBaselineEntry(
                version = version,
                artifactId = artifactId,
                lineage = lineage,
                sourceRef = sourceRef,
                sourceCommit = sourceCommit,
                jarSha256 = jarSha256,
                apiSha256 = apiSha256,
                exportsSha256 = exportsSha256,
                state = state,
            )
        }

        val duplicateKeys = entries.groupingBy { entry -> entry.version to entry.artifactId }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateKeys.isEmpty()) {
            "$sourceName contains duplicate version/artifact rows: ${duplicateKeys.sortedBy { it.toString() }}"
        }
        require(entries == entries.sortedWith(compareBy(JvmApiBaselineEntry::version, JvmApiBaselineEntry::artifactId))) {
            "$sourceName rows must be sorted by version and artifactId."
        }
        return entries
    }

    @JvmStatic
    fun verifyCoverage(
        baselineEntries: List<JvmApiBaselineEntry>,
        publicationEntries: List<PublicationInventoryEntry>,
    ) {
        val legacyArtifacts = publicationEntries
            .filter { entry ->
                entry.lineage == PublicationInventoryVerifier.LEGACY_PHYSICAL &&
                    entry.artifactKind == PublicationInventoryVerifier.JAR_KIND
            }
            .map { entry -> entry.artifactId }
            .toSet()
        val newArtifacts = publicationEntries
            .filter { entry ->
                entry.lineage == PublicationInventoryVerifier.NEW_PHYSICAL &&
                    entry.artifactKind == PublicationInventoryVerifier.JAR_KIND
            }
            .map { entry -> entry.artifactId }
            .toSet()
        val preMetadataArtifacts = legacyArtifacts - setOf("fileweft-metadata-api", "fileweft-metadata-runtime")
        val expectedByVersion = linkedMapOf(
            "0.0.1" to preMetadataArtifacts,
            "0.0.2" to preMetadataArtifacts,
            "0.0.3" to legacyArtifacts,
            NEW_BASELINE_VERSION to newArtifacts,
        )
        val actualVersions = baselineEntries.map { entry -> entry.version }.toSet()
        require(actualVersions == expectedByVersion.keys) {
            "JVM API baseline versions differ from the reviewed 0.0.1/0.0.2/0.0.3/1.0.0 set; " +
                "missing=${expectedByVersion.keys - actualVersions}, unexpected=${actualVersions - expectedByVersion.keys}."
        }
        expectedByVersion.forEach { (version, expectedArtifacts) ->
            val versionEntries = baselineEntries.filter { entry -> entry.version == version }
            val actualArtifacts = versionEntries.map { entry -> entry.artifactId }.toSet()
            require(actualArtifacts == expectedArtifacts) {
                "JVM API baseline inventory for $version differs from its reviewed artifact set; " +
                    "missing=${expectedArtifacts - actualArtifacts}, unexpected=${actualArtifacts - expectedArtifacts}."
            }
            val expectedLineage = if (version == NEW_BASELINE_VERSION) {
                PublicationInventoryVerifier.NEW_PHYSICAL
            } else {
                PublicationInventoryVerifier.LEGACY_PHYSICAL
            }
            require(versionEntries.all { entry -> entry.lineage == expectedLineage }) {
                "JVM API baseline inventory for $version must use lineage $expectedLineage."
            }
        }
    }

    private const val PENDING = "PENDING"
}
