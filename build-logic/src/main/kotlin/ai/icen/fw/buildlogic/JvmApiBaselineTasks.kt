package ai.icen.fw.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.jar.JarFile

abstract class VerifyJvmApiBaselineTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val publicationInventoryFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineInventoryFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val exportDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val candidateRepositoryGroupDirectory: DirectoryProperty

    @get:Input
    abstract val candidateVersion: Property<String>

    @get:Input
    abstract val lineage: Property<String>

    @TaskAction
    fun verifyApiBaseline() {
        val publicationEntries = PublicationInventoryVerifier.parse(publicationInventoryFile.get().asFile)
        val baselineEntries = JvmApiBaselineInventory.parse(baselineInventoryFile.get().asFile)
        JvmApiBaselineInventory.verifyCoverage(baselineEntries, publicationEntries)
        val selectedLineage = lineage.get()
        require(
            selectedLineage == PublicationInventoryVerifier.LEGACY_PHYSICAL ||
                selectedLineage == PublicationInventoryVerifier.NEW_PHYSICAL,
        ) { "Unsupported JVM API verification lineage '$selectedLineage'." }

        val selectedArtifacts = publicationEntries
            .filter { entry ->
                entry.lineage == selectedLineage && entry.artifactKind == PublicationInventoryVerifier.JAR_KIND
            }
            .map { entry -> entry.artifactId }
            .sorted()
        require(selectedArtifacts.isNotEmpty()) { "No publication artifacts use lineage $selectedLineage." }
        val relevantBaselines = baselineEntries.filter { entry -> entry.lineage == selectedLineage }
        val pending = relevantBaselines.filter { entry -> entry.state != JvmApiBaselineState.READY }
        require(pending.isEmpty()) {
            "JVM API verification is fail-closed because trusted baselines are pending: " +
                pending.joinToString { entry -> "${entry.version}:${entry.artifactId}" }
        }

        selectedArtifacts.forEach { artifactId ->
            val candidateJar = publishedBinaryJar(
                candidateRepositoryGroupDirectory.get().asFile,
                artifactId,
                candidateVersion.get(),
            )
            val exactFreezeEntry = if (selectedLineage == PublicationInventoryVerifier.NEW_PHYSICAL) {
                relevantBaselines.single { candidateEntry -> candidateEntry.artifactId == artifactId }
            } else {
                null
            }
            if (exactFreezeEntry != null) {
                JvmApiProvenance.requireDigest(
                    candidateJar,
                    requireNotNull(exactFreezeEntry.jarSha256),
                    "FlowWeft 1.0 candidate JAR for $artifactId",
                )
            }
            verifyJarIdentity(candidateJar, artifactId, candidateVersion.get())
            val candidate = JvmApiExtractor.extract(
                candidateJar,
                artifactId,
                JvmApiBaselineInventory.NEW_BASELINE_VERSION,
            )
            if (selectedLineage == PublicationInventoryVerifier.LEGACY_PHYSICAL) {
                val historical = relevantBaselines
                    .filter { entry -> entry.artifactId == artifactId }
                    .sortedBy { entry -> entry.version }
                    .map { entry -> readReviewedBaseline(entry, baselineDirectory.get().asFile) }
                JvmApiCompatibilityVerifier.verifyLegacy(artifactId, historical, candidate)
            } else {
                val entry = requireNotNull(exactFreezeEntry)
                val baseline = readReviewedBaseline(entry, baselineDirectory.get().asFile)
                val exports = readReviewedExports(entry, exportDirectory.get().asFile)
                JvmApiCompatibilityVerifier.verifyExactNew(artifactId, baseline, candidate, exports)
            }
        }
    }
}

abstract class GenerateJvmApiBaselineProposalTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val publicationInventoryFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val candidateRepositoryGroupDirectory: DirectoryProperty

    @get:Input
    abstract val candidateVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generateProposal() {
        val newArtifacts = PublicationInventoryVerifier.parse(publicationInventoryFile.get().asFile)
            .filter { entry ->
                entry.lineage == PublicationInventoryVerifier.NEW_PHYSICAL &&
                    entry.artifactKind == PublicationInventoryVerifier.JAR_KIND
            }
            .map { entry -> entry.artifactId }
            .sorted()
        require(newArtifacts.isNotEmpty()) { "No new FlowWeft artifacts exist for a 1.0 API proposal." }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val baselineOutput = output.resolve("jvm/${JvmApiBaselineInventory.NEW_BASELINE_VERSION}").apply { mkdirs() }
        val exportOutput = output.resolve("exports").apply { mkdirs() }
        val digestRows = mutableListOf("artifactId\tversion\tjarSha256\tapiSha256\texportsSha256")

        newArtifacts.forEach { artifactId ->
            val jar = publishedBinaryJar(
                candidateRepositoryGroupDirectory.get().asFile,
                artifactId,
                candidateVersion.get(),
            )
            verifyJarIdentity(jar, artifactId, candidateVersion.get())
            val snapshot = JvmApiExtractor.extract(
                jar,
                artifactId,
                JvmApiBaselineInventory.NEW_BASELINE_VERSION,
            )
            val apiFile = baselineOutput.resolve("$artifactId.api")
            apiFile.writeText(snapshot.render(), StandardCharsets.UTF_8)
            val exportsFile = exportOutput.resolve("$artifactId.exports")
            exportsFile.writeText(
                JvmApiExports(artifactId, JvmApiBaselineState.READY, snapshot.publicClassNames).render(),
                StandardCharsets.UTF_8,
            )
            digestRows += listOf(
                artifactId,
                candidateVersion.get(),
                JvmApiExtractor.sha256(jar),
                JvmApiExtractor.sha256(apiFile),
                JvmApiExtractor.sha256(exportsFile),
            ).joinToString("\t")
        }
        output.resolve("candidate-provenance.sha256.tsv")
            .writeText(digestRows.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
        output.resolve("README.txt").writeText(
            "Generated proposal only. Review every exported class and API record, bind the final stable JAR " +
            "JAR/API/exports SHA-256 values and exact source commit in " +
                "gradle/compatibility/jvm-api-baselines.tsv, then copy " +
                "approved files with UTF-8 LF. Generation alone never approves a public API.\n",
            StandardCharsets.UTF_8,
        )
    }
}

abstract class ImportTrustedJvmApiBaselinesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val publicationInventoryFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineInventoryFile: RegularFileProperty

    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val trustedRepositoryDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun importTrustedBaselines() {
        require(trustedRepositoryDirectory.isPresent) {
            "Set -PflowweftTrustedBaselineRepository=<isolated Maven repository> before importing baselines."
        }
        val publicationEntries = PublicationInventoryVerifier.parse(publicationInventoryFile.get().asFile)
        val baselineEntries = JvmApiBaselineInventory.parse(baselineInventoryFile.get().asFile)
        JvmApiBaselineInventory.verifyCoverage(baselineEntries, publicationEntries)
        val legacyEntries = baselineEntries.filter {
            entry -> entry.lineage == PublicationInventoryVerifier.LEGACY_PHYSICAL
        }
        val unpinned = legacyEntries.filter { entry -> entry.sourceCommit == null || entry.jarSha256 == null }
        require(unpinned.isEmpty()) {
            "Trusted JVM API import is fail-closed until every historical source commit and JAR digest is pinned: " +
                unpinned.joinToString { entry -> "${entry.version}:${entry.artifactId}" }
        }

        val trustedRoot = trustedRepositoryDirectory.get().asFile.canonicalFile
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val provenanceRows = mutableListOf(JvmApiBaselineInventory.HEADER)
        legacyEntries.forEach { entry ->
            val jar = trustedRoot.resolve(
                "ai/icen/${entry.artifactId}/${entry.version}/${entry.artifactId}-${entry.version}.jar",
            ).canonicalFile
            require(jar.toPath().startsWith(trustedRoot.toPath())) {
                "Trusted JVM API artifact escaped the configured repository: ${jar.absolutePath}"
            }
            require(jar.isFile && jar.length() > 0L) {
                "Trusted released JAR is missing: ${jar.absolutePath}"
            }
            JvmApiProvenance.requireDigest(
                jar,
                requireNotNull(entry.jarSha256),
                "Trusted released JAR for ${entry.artifactId}:${entry.version}",
            )
            verifyJarIdentity(jar, entry.artifactId, entry.version)
            val snapshot = JvmApiExtractor.extract(jar, entry.artifactId, entry.version)
            val destination = output.resolve("jvm/${entry.version}/${entry.artifactId}.api")
            destination.parentFile.mkdirs()
            destination.writeText(snapshot.render(), StandardCharsets.UTF_8)
            val apiDigest = JvmApiExtractor.sha256(destination)
            if (entry.state == JvmApiBaselineState.READY) {
                require(apiDigest == entry.apiSha256) {
                    "Regenerated JVM API baseline digest mismatch for ${entry.artifactId}:${entry.version}; " +
                        "expected=${entry.apiSha256}, actual=$apiDigest."
                }
            }
            provenanceRows += listOf(
                entry.version,
                entry.artifactId,
                entry.lineage,
                entry.sourceRef,
                requireNotNull(entry.sourceCommit),
                requireNotNull(entry.jarSha256),
                apiDigest,
                JvmApiBaselineInventory.NO_EXPORTS,
                entry.state.name.lowercase(),
            ).joinToString("\t")
        }
        output.resolve("SOURCE-PROVENANCE.tsv")
            .writeText(provenanceRows.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
    }
}

private fun readReviewedBaseline(entry: JvmApiBaselineEntry, baselineRoot: File): JvmApiSnapshot {
    require(entry.state == JvmApiBaselineState.READY && entry.jarSha256 != null && entry.apiSha256 != null) {
        "JVM API baseline is not trusted for ${entry.version}:${entry.artifactId}."
    }
    val file = baselineRoot.resolve("${entry.version}/${entry.artifactId}.api")
    JvmApiProvenance.requireDigest(
        file,
        requireNotNull(entry.apiSha256),
        "JVM API baseline for ${entry.version}:${entry.artifactId}",
    )
    val snapshot = JvmApiSnapshot.read(file)
    require(snapshot.artifactId == entry.artifactId && snapshot.baselineVersion == entry.version) {
        "JVM API baseline header does not match provenance for ${entry.version}:${entry.artifactId}."
    }
    return snapshot
}

private fun readReviewedExports(entry: JvmApiBaselineEntry, exportRoot: File): JvmApiExports {
    require(entry.state == JvmApiBaselineState.READY && entry.exportsSha256 != null &&
        entry.exportsSha256 != JvmApiBaselineInventory.NO_EXPORTS
    ) { "JVM API exports are not trusted for ${entry.version}:${entry.artifactId}." }
    val file = exportRoot.resolve("${entry.artifactId}.exports")
    JvmApiProvenance.requireDigest(
        file,
        requireNotNull(entry.exportsSha256),
        "JVM API exports for ${entry.version}:${entry.artifactId}",
    )
    return JvmApiExports.read(file).also { exports ->
        require(exports.artifactId == entry.artifactId && exports.state == JvmApiBaselineState.READY) {
            "JVM API exports header does not match provenance for ${entry.version}:${entry.artifactId}."
        }
    }
}

private fun publishedBinaryJar(groupDirectory: File, artifactId: String, version: String): File {
    val versionDirectory = groupDirectory.resolve("$artifactId/$version")
    require(versionDirectory.isDirectory) {
        "Published candidate directory is missing for $artifactId:$version: ${versionDirectory.absolutePath}"
    }
    val candidates = versionDirectory.listFiles().orEmpty()
        .filter { file ->
            file.isFile && file.extension == "jar" &&
                !file.name.endsWith("-sources.jar") && !file.name.endsWith("-javadoc.jar")
        }
    require(candidates.size == 1) {
        "Expected one published binary JAR for $artifactId:$version, found ${candidates.map { it.name }}."
    }
    return candidates.single()
}

private fun verifyJarIdentity(jarFile: File, artifactId: String, version: String) {
    JarFile(jarFile, false).use { jar ->
        val attributes = jar.manifest?.mainAttributes
            ?: error("Published candidate JAR has no manifest: ${jarFile.absolutePath}")
        require(attributes.getValue("Implementation-Title") == artifactId) {
            "Published candidate JAR title does not match $artifactId: ${jarFile.absolutePath}"
        }
        require(attributes.getValue("Implementation-Version") == version) {
            "Published candidate JAR version does not match $version: ${jarFile.absolutePath}"
        }
    }
}
