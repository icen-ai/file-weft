package ai.icen.fw.buildlogic

import groovy.json.JsonSlurper
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Verifies the exact FlowWeft Maven publication contract for all public modules.
 *
 * The verifier is intentionally strict: every artifact, checksum, metadata file,
 * POM element, Gradle module metadata field, and JAR entry must match the reviewed
 * release contract. It throws [IllegalArgumentException] on the first violation.
 */
class PublishedReleaseRepositoryVerifier(
    private val releaseGroup: String,
    private val releaseVersion: String,
    private val publishableModuleNames: Set<String>,
    private val groupDirectory: File,
    private val licenseBytes: ByteArray,
    private val noticeBytes: ByteArray,
    private val projectHomepage: String,
    private val projectScmConnection: String,
    private val apacheLicenseName: String,
    private val apacheLicenseUrl: String,
    private val releaseScmTag: String,
    private val withdrawnMavenGroup: String,
    private val withdrawnJvmPath: String,
) {

    fun verify() {
        require(groupDirectory.isDirectory) {
            "Published Maven group directory is missing: ${groupDirectory.absolutePath}"
        }
        val expectedModules = publishableModuleNames.sorted().toSet()
        val actualModules = groupDirectory.listFiles()
            .orEmpty()
            .filter { file -> file.isDirectory }
            .map { file -> file.name }
            .toSet()
        require(actualModules == expectedModules) {
            "Published Maven modules differ from the reviewed public set; " +
                "missing=${expectedModules - actualModules}, unexpected=${actualModules - expectedModules}."
        }

        val resolvedSnapshotVersions = mutableSetOf<String>()
        val classOwners = linkedMapOf<String, String>()
        val duplicateClassOwners = linkedMapOf<String, MutableSet<String>>()
        expectedModules.sorted().forEach { moduleName ->
            verifyModule(moduleName, resolvedSnapshotVersions).forEach { classEntry ->
                val previousOwner = classOwners.putIfAbsent(classEntry, moduleName)
                if (previousOwner != null && previousOwner != moduleName) {
                    duplicateClassOwners.getOrPut(classEntry) { linkedSetOf(previousOwner) } += moduleName
                }
            }
        }
        require(duplicateClassOwners.isEmpty()) {
            val sample = duplicateClassOwners.entries
                .take(20)
                .joinToString { (classEntry, owners) -> "$classEntry -> ${owners.sorted()}" }
            "Published binary JARs contain classes owned by more than one module: $sample"
        }
        if (releaseVersion.endsWith("-SNAPSHOT")) {
            require(resolvedSnapshotVersions.size == 1) {
                "Published modules resolve to mixed SNAPSHOT identities: $resolvedSnapshotVersions"
            }
        }
    }

    private fun verifyModule(moduleName: String, resolvedSnapshotVersions: MutableSet<String>): Set<String> {
        val moduleDirectory = groupDirectory.resolve(moduleName)
        val publishedVersions = moduleDirectory.listFiles()
            .orEmpty()
            .filter { file -> file.isDirectory }
            .map { file -> file.name }
            .toSet()
        require(publishedVersions == setOf(releaseVersion)) {
            "Published versions for $releaseGroup:$moduleName must contain only $releaseVersion: $publishedVersions"
        }
        val versionDirectory = moduleDirectory.resolve(releaseVersion)
        val publishedArtifactVersion = if (releaseVersion.endsWith("-SNAPSHOT")) {
            verifySnapshotMetadata(moduleDirectory, versionDirectory, moduleName, resolvedSnapshotVersions)
        } else {
            releaseVersion
        }

        val artifactPrefix = "$moduleName-$publishedArtifactVersion"
        val binaryJar = versionDirectory.resolve("$artifactPrefix.jar")
        val sourcesJar = versionDirectory.resolve("$artifactPrefix-sources.jar")
        val pomFile = versionDirectory.resolve("$artifactPrefix.pom")
        val moduleFile = versionDirectory.resolve("$artifactPrefix.module")
        val expectedFiles = mutableSetOf<File>()
        listOf(binaryJar, sourcesJar, pomFile, moduleFile).forEach { artifact ->
            require(artifact.isFile && artifact.length() > 0L) {
                "Published artifact is missing or empty: ${artifact.absolutePath}"
            }
            expectedFiles += artifact
            checksumAlgorithms.forEach { (algorithm, suffix) ->
                val checksumFile = versionDirectory.resolve("${artifact.name}.$suffix")
                require(checksumFile.isFile) {
                    "Published checksum is missing: ${checksumFile.absolutePath}"
                }
                expectedFiles += checksumFile
                val actualChecksum = digest(algorithm, artifact.readBytes())
                require(checksumFile.readText(Charsets.UTF_8).trim() == actualChecksum) {
                    "Published checksum does not match ${artifact.name}: ${checksumFile.name}"
                }
            }
        }
        if (releaseVersion.endsWith("-SNAPSHOT")) {
            val metadataFile = versionDirectory.resolve("maven-metadata.xml")
            expectedFiles += metadataFile
            expectedFiles += versionDirectory.resolve("${metadataFile.name}.md5")
            expectedFiles += versionDirectory.resolve("${metadataFile.name}.sha1")
            expectedFiles += versionDirectory.resolve("${metadataFile.name}.sha256")
            expectedFiles += versionDirectory.resolve("${metadataFile.name}.sha512")
        }
        val actualFiles = versionDirectory.listFiles().orEmpty().filter { it.isFile }.toSet()
        require(actualFiles == expectedFiles) {
            "Published repository inventory for $moduleName differs from the expected contract; " +
                "unexpected=${(actualFiles - expectedFiles).map { it.name }}, " +
                "missing=${(expectedFiles - actualFiles).map { it.name }}."
        }

        val binaryClasses = verifyJar(binaryJar, moduleName, expectSources = false)
        verifyJar(sourcesJar, moduleName, expectSources = true)
        verifyPom(pomFile, moduleName, expectedModules)
        verifyModuleMetadata(moduleFile, moduleName)
        return binaryClasses
    }

    private fun verifySnapshotMetadata(
        moduleDirectory: File,
        versionDirectory: File,
        moduleName: String,
        resolvedSnapshotVersions: MutableSet<String>,
    ): String {
        val metadataFile = versionDirectory.resolve("maven-metadata.xml")
        require(metadataFile.isFile && metadataFile.length() > 0L) {
            "SNAPSHOT metadata is missing or empty: ${metadataFile.absolutePath}"
        }
        val metadataDocument = secureDocumentBuilderFactory().newDocumentBuilder().parse(metadataFile)
        val metadata = metadataDocument.documentElement
        require(requiredSingleChildText(metadata, "groupId", metadataFile) == releaseGroup) {
            "SNAPSHOT metadata has the wrong group: ${metadataFile.name}"
        }
        require(requiredSingleChildText(metadata, "artifactId", metadataFile) == moduleName) {
            "SNAPSHOT metadata has the wrong artifact: ${metadataFile.name}"
        }
        require(requiredSingleChildText(metadata, "version", metadataFile) == releaseVersion) {
            "SNAPSHOT metadata has the wrong version: ${metadataFile.name}"
        }
        val versioning = requiredSingleChild(metadata, "versioning", metadataFile)
        val snapshot = requiredSingleChild(versioning, "snapshot", metadataFile)
        val timestamp = requiredSingleChildText(snapshot, "timestamp", metadataFile)
        val buildNumber = requiredSingleChildText(snapshot, "buildNumber", metadataFile)
        require(timestampRegex.matches(timestamp)) {
            "SNAPSHOT metadata has an invalid timestamp: $timestamp"
        }
        require(buildNumberRegex.matches(buildNumber)) {
            "SNAPSHOT metadata has an invalid build number: $buildNumber"
        }
        val expectedUpdated = timestamp.replace(".", "")
        require(requiredSingleChildText(versioning, "lastUpdated", metadataFile) == expectedUpdated) {
            "SNAPSHOT metadata lastUpdated does not match its timestamp: ${metadataFile.name}"
        }
        val snapshotVersions = requiredSingleChild(versioning, "snapshotVersions", metadataFile)
        val entries = directChildren(snapshotVersions, "snapshotVersion").map { entry ->
            val extension = requiredSingleChildText(entry, "extension", metadataFile)
            val classifier = optionalSingleChildText(entry, "classifier", metadataFile).orEmpty()
            val value = requiredSingleChildText(entry, "value", metadataFile)
            require(requiredSingleChildText(entry, "updated", metadataFile) == expectedUpdated) {
                "SNAPSHOT metadata entry update does not match its timestamp: ${metadataFile.name}"
            }
            (extension to classifier) to value
        }
        val entriesByType = entries.toMap()
        require(entriesByType.size == entries.size) {
            "SNAPSHOT metadata contains duplicate extension/classifier entries: ${metadataFile.name}"
        }
        require(entriesByType.keys == expectedSnapshotTypes) {
            "SNAPSHOT metadata artifact types differ for $releaseGroup:$moduleName; " +
                "missing=${expectedSnapshotTypes - entriesByType.keys}, unexpected=${entriesByType.keys - expectedSnapshotTypes}."
        }
        val moduleResolvedVersions = entriesByType.values.toSet()
        require(moduleResolvedVersions.size == 1) {
            "SNAPSHOT metadata resolves inconsistent artifact versions: $moduleResolvedVersions"
        }
        val expectedResolvedVersion =
            "${releaseVersion.removeSuffix("-SNAPSHOT")}-$timestamp-$buildNumber"
        return moduleResolvedVersions.single().also { resolvedVersion ->
            require(resolvedVersion == expectedResolvedVersion) {
                "SNAPSHOT metadata artifact version does not match timestamp/buildNumber: $resolvedVersion"
            }
            resolvedSnapshotVersions += resolvedVersion
        }
    }

    private fun verifyJar(archive: File, moduleName: String, expectSources: Boolean): Set<String> =
        ZipFile(archive).use { jar ->
            val fileEntries = jar.entries().asSequence()
                .filterNot { entry -> entry.isDirectory }
                .toList()
            val dangerousEntries = fileEntries.map { entry -> entry.name }.filter(::isDangerousJarEntry)
            require(dangerousEntries.isEmpty()) {
                "Published JAR contains dangerous entries: ${dangerousEntries.take(10)} in ${archive.name}"
            }
            val duplicateEntries = fileEntries
                .groupingBy { entry -> entry.name }
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys
            require(duplicateEntries.isEmpty()) {
                "Published JAR contains duplicate entries: $duplicateEntries in ${archive.name}"
            }
            val entries = fileEntries.associateBy { entry -> entry.name }
            val licenseEntry = entries["META-INF/LICENSE"]
            val noticeEntry = entries["META-INF/NOTICE"]
            require(licenseEntry != null && noticeEntry != null) {
                "Published JAR must contain META-INF/LICENSE and META-INF/NOTICE: ${archive.name}"
            }
            val packagedLicense = jar.getInputStream(licenseEntry).use { stream -> stream.readBytes() }
            val packagedNotice = jar.getInputStream(noticeEntry).use { stream -> stream.readBytes() }
            require(packagedLicense.contentEquals(licenseBytes)) {
                "Published JAR license differs from the reviewed root LICENSE: ${archive.name}"
            }
            require(packagedNotice.contentEquals(noticeBytes)) {
                "Published JAR notice differs from the reviewed root NOTICE: ${archive.name}"
            }
            val legacyEntries = entries.keys.filter { entry -> entry.startsWith("$withdrawnJvmPath/") }
            require(legacyEntries.isEmpty()) {
                "Published JAR contains legacy $withdrawnMavenGroup package entries: ${archive.name}"
            }
            if (expectSources) {
                require(entries.keys.any { entry -> entry.endsWith(".kt") || entry.endsWith(".java") }) {
                    "Published sources JAR contains no Kotlin or Java source: ${archive.name}"
                }
                emptySet()
            } else {
                val classEntries = entries.keys
                    .filter { entry -> entry.endsWith(".class") }
                    .toSortedSet()
                require(classEntries.isNotEmpty()) {
                    "Published binary JAR contains no class files: ${archive.name}"
                }
                val manifestEntry = entries["META-INF/MANIFEST.MF"]
                    ?: error("Published binary JAR has no manifest: ${archive.name}")
                val manifest = jar.getInputStream(manifestEntry)
                    .bufferedReader(Charsets.UTF_8)
                    .use { reader -> reader.readText() }
                require(manifest.contains("Implementation-Title: $moduleName")) {
                    "Published binary JAR manifest has the wrong title: ${archive.name}"
                }
                require(manifest.contains("Implementation-Version: $releaseVersion")) {
                    "Published binary JAR manifest has the wrong version: ${archive.name}"
                }
                classEntries
            }
        }

    private fun verifyPom(pomFile: File, moduleName: String, expectedModules: Set<String>) {
        val pomDocument = secureDocumentBuilderFactory().newDocumentBuilder().parse(pomFile)
        val pomText = pomFile.readText(Charsets.UTF_8)
        require(!pomText.contains(withdrawnMavenGroup)) {
            "POM contains the withdrawn $withdrawnMavenGroup group: ${pomFile.name}"
        }
        require(releaseVersion.endsWith("-SNAPSHOT") || !pomText.contains("-SNAPSHOT")) {
            "Release POM contains a SNAPSHOT dependency or coordinate: ${pomFile.name}"
        }
        val projectElement = pomDocument.documentElement
        require(directChildText(projectElement, "groupId") == releaseGroup)
        require(directChildText(projectElement, "artifactId") == moduleName)
        require(directChildText(projectElement, "version") == releaseVersion)
        require(directChildText(projectElement, "name") == "FlowWeft module $moduleName") {
            "POM has the wrong FlowWeft display name: ${pomFile.name}"
        }
        require(
            directChildText(projectElement, "description") ==
                "FlowWeft enterprise file and workflow infrastructure module $moduleName.",
        ) {
            "POM has the wrong FlowWeft description: ${pomFile.name}"
        }
        require(directChildText(projectElement, "url") == projectHomepage)

        val licenseNodes = pomDocument.getElementsByTagNameNS("*", "license")
        require(licenseNodes.length == 1) { "POM must declare exactly one license: ${pomFile.name}" }
        val licenseElement = licenseNodes.item(0) as Element
        require(directChildText(licenseElement, "name") == apacheLicenseName)
        require(directChildText(licenseElement, "url") == apacheLicenseUrl)
        require(directChildText(licenseElement, "distribution") == "repo")

        val developerNodes = pomDocument.getElementsByTagNameNS("*", "developer")
        require(developerNodes.length == 1) { "POM must declare exactly one developer: ${pomFile.name}" }
        val developerElement = developerNodes.item(0) as Element
        require(directChildText(developerElement, "id") == "icen.ai")
        require(directChildText(developerElement, "name") == "icen.ai")
        require(directChildText(developerElement, "organization") == "icen.ai")
        require(directChildText(developerElement, "email") == "support@icen.ai")

        val scmNodes = pomDocument.getElementsByTagNameNS("*", "scm")
        require(scmNodes.length == 1) { "POM must declare exactly one SCM block: ${pomFile.name}" }
        val scmElement = scmNodes.item(0) as Element
        require(directChildText(scmElement, "connection") == projectScmConnection)
        require(directChildText(scmElement, "developerConnection") == projectScmConnection)
        require(directChildText(scmElement, "url") == projectHomepage)
        require(directChildText(scmElement, "tag") == releaseScmTag)

        val dependencyNodes = pomDocument.getElementsByTagNameNS("*", "dependency")
        (0 until dependencyNodes.length)
            .asSequence()
            .map { index -> dependencyNodes.item(index) as Element }
            .filter { dependency -> directChildText(dependency, "groupId") == releaseGroup }
            .forEach { dependency ->
                val dependencyArtifact = directChildText(dependency, "artifactId")
                require(dependencyArtifact in expectedModules) {
                    "POM contains an unknown internal dependency: $dependencyArtifact in ${pomFile.name}"
                }
                require(directChildText(dependency, "version") == releaseVersion) {
                    "POM internal dependency must use $releaseVersion: ${pomFile.name} -> $dependencyArtifact"
                }
            }
    }

    private fun verifyModuleMetadata(moduleFile: File, moduleName: String) {
        val moduleMetadata = JsonSlurper().parse(moduleFile) as Map<*, *>
        val component = moduleMetadata["component"] as? Map<*, *>
            ?: error("Gradle module metadata has no component block: ${moduleFile.name}")
        require(component["group"] == releaseGroup)
        require(component["module"] == moduleName)
        require(component["version"] == releaseVersion)
        val moduleText = moduleFile.readText(Charsets.UTF_8)
        require(!moduleText.contains(withdrawnMavenGroup)) {
            "Gradle module metadata contains the withdrawn group: ${moduleFile.name}"
        }
        require(releaseVersion.endsWith("-SNAPSHOT") || !moduleText.contains("-SNAPSHOT")) {
            "Release Gradle module metadata contains a SNAPSHOT dependency or coordinate: ${moduleFile.name}"
        }
    }

    private val expectedModules: Set<String>
        get() = publishableModuleNames

    companion object {
        private val checksumAlgorithms = linkedMapOf(
            "MD5" to "md5",
            "SHA-1" to "sha1",
            "SHA-256" to "sha256",
            "SHA-512" to "sha512",
        )
        private val expectedSnapshotTypes = setOf(
            "jar" to "",
            "jar" to "sources",
            "pom" to "",
            "module" to "",
        )
        private val timestampRegex = Regex("^\\d{8}\\.\\d{6}$")
        private val buildNumberRegex = Regex("^[1-9]\\d*$")

        internal fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                isExpandEntityReferences = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }

        internal fun directChildren(parent: Element, localName: String): List<Element> =
            (0 until parent.childNodes.length)
                .asSequence()
                .map { index -> parent.childNodes.item(index) }
                .filterIsInstance<Element>()
                .filter { child -> child.localName == localName || child.nodeName == localName }
                .toList()

        internal fun directChild(parent: Element, localName: String): Element? =
            directChildren(parent, localName).firstOrNull()

        internal fun directChildText(parent: Element, localName: String): String? =
            directChild(parent, localName)?.textContent?.trim()

        internal fun optionalSingleChildText(parent: Element, localName: String, source: File): String? {
            val children = directChildren(parent, localName)
            require(children.size <= 1) {
                "${source.name} contains duplicate direct <$localName> elements."
            }
            return children.singleOrNull()?.textContent?.trim()
        }

        internal fun requiredSingleChild(parent: Element, localName: String, source: File): Element {
            val children = directChildren(parent, localName)
            require(children.size == 1) {
                "${source.name} must contain exactly one direct <$localName> element; found ${children.size}."
            }
            return children.single()
        }

        internal fun requiredSingleChildText(parent: Element, localName: String, source: File): String =
            optionalSingleChildText(parent, localName, source)
                ?.takeIf { value -> value.isNotEmpty() }
                ?: error("${source.name} must contain one non-empty direct <$localName> element.")

        internal fun digest(algorithm: String, bytes: ByteArray): String =
            MessageDigest.getInstance(algorithm)
                .digest(bytes)
                .joinToString(separator = "") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }

        internal fun isDangerousJarEntry(entryName: String): Boolean =
            entryName.startsWith("/") ||
                entryName.startsWith("\\") ||
                ".." in entryName ||
                '\\' in entryName ||
                entryName.contains(":") ||
                entryName.isBlank()
    }
}
