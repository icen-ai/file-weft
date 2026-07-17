package ai.icen.fw.buildlogic

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublishedReleaseRepositoryVerifierFixtureTest {

    private val licenseBytes = "FileWeft License\n".toByteArray(StandardCharsets.UTF_8)
    private val noticeBytes = "FileWeft Notice\n".toByteArray(StandardCharsets.UTF_8)
    private val homepage = "https://cnb.cool/china.ai/file-weft"
    private val scmConnection = "scm:git:https://cnb.cool/china.ai/file-weft.git"
    private val apacheLicenseName = "Apache License, Version 2.0"
    private val apacheLicenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt"

    @Test
    fun `accepts a valid stable release repository`() {
        withFixtureRepository(version = "0.0.1") { repo, modules ->
            verifier(repo, "0.0.1", modules).verify()
        }
    }

    @Test
    fun `rejects a corrupted artifact checksum`() {
        withFixtureRepository(version = "0.0.1") { repo, modules ->
            val jar = repo.resolve("ai/icen/${modules.single()}/0.0.1/${modules.single()}-0.0.1.jar")
            repo.resolve("ai/icen/${modules.single()}/0.0.1/${jar.name}.sha256")
                .writeText("0000000000000000000000000000000000000000000000000000000000000000", StandardCharsets.UTF_8)

            val failure = assertFailsWith<IllegalArgumentException> {
                verifier(repo, "0.0.1", modules).verify()
            }
            assertTrue(failure.message.orEmpty().contains("checksum does not match"), failure.message)
        }
    }

    @Test
    fun `rejects a missing artifact file`() {
        withFixtureRepository(version = "0.0.1") { repo, modules ->
            repo.resolve("ai/icen/${modules.single()}/0.0.1/${modules.single()}-0.0.1-sources.jar").delete()

            val failure = assertFailsWith<IllegalArgumentException> {
                verifier(repo, "0.0.1", modules).verify()
            }
            assertTrue(failure.message.orEmpty().contains("missing or empty"), failure.message)
        }
    }

    @Test
    fun `rejects a JAR containing a path traversal entry`() {
        withFixtureRepository(version = "0.0.1", binaryJar = jarWithPathTraversal()) { repo, modules ->
            val failure = assertFailsWith<IllegalArgumentException> {
                verifier(repo, "0.0.1", modules).verify()
            }
            assertTrue(failure.message.orEmpty().contains("dangerous entries"), failure.message)
        }
    }

    @Test
    fun `rejects a stable release repository containing a SNAPSHOT version directory`() {
        withFixtureRepository(version = "0.0.1") { repo, modules ->
            repo.resolve("ai/icen/${modules.single()}/0.0.2-SNAPSHOT").mkdirs()

            val failure = assertFailsWith<IllegalArgumentException> {
                verifier(repo, "0.0.1", modules).verify()
            }
            assertTrue(failure.message.orEmpty().contains("must contain only 0.0.1"), failure.message)
        }
    }

    @Test
    fun `rejects duplicate extension classifier entries in SNAPSHOT metadata`() {
        withSnapshotRepository(
            modules = listOf("fileweft-core" to SnapshotIdentity("20990101.020304", "1")),
        ) { repo, modules ->
            val metadata = repo.resolve("ai/icen/fileweft-core/0.0.2-SNAPSHOT/maven-metadata.xml")
            metadata.writeText(
                snapshotMetadataText("fileweft-core", "20990101.020304", "1").replace(
                    "</snapshotVersions>",
                    "<snapshotVersion><extension>jar</extension><value>0.0.2-20990101.020304-1</value><updated>20990101020304</updated></snapshotVersion></snapshotVersions>",
                ),
                StandardCharsets.UTF_8,
            )
            rewriteChecksums(metadata)

            val failure = assertFailsWith<IllegalArgumentException> {
                verifier(repo, "0.0.2-SNAPSHOT", modules).verify()
            }
            assertTrue(failure.message.orEmpty().contains("duplicate extension/classifier entries"), failure.message)
        }
    }

    @Test
    fun `rejects a stable release POM containing a SNAPSHOT coordinate`() {
        withFixtureRepository(version = "0.0.1") { repo, modules ->
            val pom = repo.resolve("ai/icen/${modules.single()}/0.0.1/${modules.single()}-0.0.1.pom")
            pom.writeText(pomText(modules.single(), "0.0.1").replace("</dependencies>", "<dependency><groupId>org.example</groupId><artifactId>leak</artifactId><version>1.0-SNAPSHOT</version></dependency></dependencies>"), StandardCharsets.UTF_8)
            rewriteChecksums(pom)

            val failure = assertFailsWith<IllegalArgumentException> {
                verifier(repo, "0.0.1", modules).verify()
            }
            assertTrue(failure.message.orEmpty().contains("SNAPSHOT"), failure.message)
        }
    }

    @Test
    fun `rejects a binary JAR containing no classes`() {
        withFixtureRepository(
            version = "0.0.1",
            binaryJar = validBinaryJar("fileweft-core", "0.0.1", classEntries = emptyList()),
        ) { repo, modules ->
            val failure = assertFailsWith<IllegalArgumentException> {
                verifier(repo, "0.0.1", modules).verify()
            }
            assertTrue(failure.message.orEmpty().contains("no class files"), failure.message)
        }
    }

    @Test
    fun `rejects duplicate class ownership across published modules`() {
        val modules = sortedSetOf("fileweft-core", "fileweft-spi")
        withStableRepository(
            version = "0.0.1",
            modules = modules,
            binaryJarForModule = { module ->
                validBinaryJar(
                    module,
                    "0.0.1",
                    classEntries = listOf("ai/icen/fw/shared/Duplicate.class"),
                )
            },
        ) { repo ->
            val failure = assertFailsWith<IllegalArgumentException> {
                verifier(repo, "0.0.1", modules).verify()
            }
            assertTrue(failure.message.orEmpty().contains("more than one module"), failure.message)
            assertTrue(failure.message.orEmpty().contains("ai/icen/fw/shared/Duplicate.class"), failure.message)
        }
    }

    @Test
    fun `rejects a POM with the legacy product display name`() {
        withFixtureRepository(version = "0.0.1") { repo, modules ->
            val module = modules.single()
            val pom = repo.resolve("ai/icen/$module/0.0.1/$module-0.0.1.pom")
            pom.writeText(
                pomText(module, "0.0.1").replace("FlowWeft module $module", "FileWeft module $module"),
                StandardCharsets.UTF_8,
            )
            rewriteChecksums(pom)

            val failure = assertFailsWith<IllegalArgumentException> {
                verifier(repo, "0.0.1", modules).verify()
            }
            assertTrue(failure.message.orEmpty().contains("display name"), failure.message)
        }
    }

    @Test
    fun `rejects a mixed SNAPSHOT identity across modules`() {
        withSnapshotRepository(
            modules = listOf(
                "fileweft-core" to SnapshotIdentity("20990101.020304", "1"),
                "fileweft-spi" to SnapshotIdentity("20990102.030405", "2"),
            ),
        ) { repo, modules ->
            val failure = assertFailsWith<IllegalArgumentException> {
                verifier(repo, "0.0.2-SNAPSHOT", modules).verify()
            }
            assertTrue(failure.message.orEmpty().contains("mixed SNAPSHOT identities"), failure.message)
        }
    }

    @Test
    fun `rejects a SNAPSHOT repository missing metadata checksums`() {
        withSnapshotRepository(
            modules = listOf("fileweft-core" to SnapshotIdentity("20990101.020304", "1")),
        ) { repo, modules ->
            repo.resolve("ai/icen/fileweft-core/0.0.2-SNAPSHOT/maven-metadata.xml.sha1").delete()

            val failure = assertFailsWith<IllegalArgumentException> {
                verifier(repo, "0.0.2-SNAPSHOT", modules).verify()
            }
            assertTrue(failure.message.orEmpty().contains("inventory"), failure.message)
        }
    }

    @Test
    fun `rejects an XXE attempt in SNAPSHOT metadata`() {
        withSnapshotRepository(
            modules = listOf("fileweft-core" to SnapshotIdentity("20990101.020304", "1")),
        ) { repo, modules ->
            val metadata = repo.resolve("ai/icen/fileweft-core/0.0.2-SNAPSHOT/maven-metadata.xml")
            metadata.writeText(
                """
                <!DOCTYPE metadata [<!ENTITY xxe SYSTEM "file:///definitely-not-readable">]>
                <metadata>&xxe;</metadata>
                """.trimIndent(),
                StandardCharsets.UTF_8,
            )
            rewriteChecksums(metadata)

            val failure = assertFailsWith<Throwable> {
                verifier(repo, "0.0.2-SNAPSHOT", modules).verify()
            }
            assertTrue(
                failure.message.orEmpty().contains("DOCTYPE is disallowed") || failure.message.orEmpty().contains("disallow-doctype-decl"),
                failure.message,
            )
        }
    }

    private fun verifier(repo: File, version: String, modules: Set<String>): PublishedReleaseRepositoryVerifier =
        PublishedReleaseRepositoryVerifier(
            releaseGroup = "ai.icen",
            releaseVersion = version,
            publishableModuleNames = modules,
            groupDirectory = repo.resolve("ai/icen"),
            licenseBytes = licenseBytes,
            noticeBytes = noticeBytes,
            projectHomepage = homepage,
            projectScmConnection = scmConnection,
            apacheLicenseName = apacheLicenseName,
            apacheLicenseUrl = apacheLicenseUrl,
            releaseScmTag = "v$version",
            withdrawnMavenGroup = listOf("com", "fileweft").joinToString("."),
            withdrawnJvmPath = listOf("com", "fileweft").joinToString("/"),
        )

    private fun withFixtureRepository(
        version: String,
        module: String = "fileweft-core",
        binaryJar: ByteArray = validBinaryJar(module, version),
        action: (File, Set<String>) -> Unit,
    ) {
        val repo = Files.createTempDirectory("fileweft-release-repo-").toFile()
        try {
            val modules = setOf(module)
            writeModule(repo, module, version, binaryJar)
            action(repo, modules)
        } finally {
            repo.deleteRecursively()
        }
    }

    private fun withStableRepository(
        version: String,
        modules: Set<String>,
        binaryJarForModule: (String) -> ByteArray,
        action: (File) -> Unit,
    ) {
        val repo = Files.createTempDirectory("fileweft-stable-release-repo-").toFile()
        try {
            modules.forEach { module -> writeModule(repo, module, version, binaryJarForModule(module)) }
            action(repo)
        } finally {
            repo.deleteRecursively()
        }
    }

    private data class SnapshotIdentity(val timestamp: String, val buildNumber: String)

    private fun withSnapshotRepository(
        modules: List<Pair<String, SnapshotIdentity>>,
        action: (File, Set<String>) -> Unit,
    ) {
        val repo = Files.createTempDirectory("fileweft-snapshot-repo-").toFile()
        try {
            modules.forEach { (module, identity) -> writeSnapshotModule(repo, module, identity) }
            action(repo, modules.map { (module, _) -> module }.toSortedSet())
        } finally {
            repo.deleteRecursively()
        }
    }

    private fun writeModule(repo: File, module: String, version: String, binaryJar: ByteArray) {
        val dir = repo.resolve("ai/icen/$module/$version").apply { mkdirs() }
        val binaryFile = dir.resolve("$module-$version.jar").apply { writeBytes(binaryJar) }
        val sourcesFile = dir.resolve("$module-$version-sources.jar").apply { writeBytes(validSourcesJar()) }
        val pomFile = dir.resolve("$module-$version.pom").apply { writeText(pomText(module, version), StandardCharsets.UTF_8) }
        val moduleFile = dir.resolve("$module-$version.module").apply { writeText(moduleMetadataText(module, version), StandardCharsets.UTF_8) }
        listOf(binaryFile, sourcesFile, pomFile, moduleFile).forEach(::writeChecksums)
    }

    private fun writeSnapshotModule(
        repo: File,
        module: String,
        identity: SnapshotIdentity,
        version: String = "0.0.2-SNAPSHOT",
    ) {
        val dir = repo.resolve("ai/icen/$module/$version").apply { mkdirs() }
        val metadataFile = dir.resolve("maven-metadata.xml").apply {
            writeText(snapshotMetadataText(module, identity.timestamp, identity.buildNumber), StandardCharsets.UTF_8)
        }
        val resolved = "${version.removeSuffix("-SNAPSHOT")}-${identity.timestamp}-${identity.buildNumber}"
        val binaryFile = dir.resolve("$module-$resolved.jar").apply { writeBytes(validBinaryJar(module, version)) }
        val sourcesFile = dir.resolve("$module-$resolved-sources.jar").apply { writeBytes(validSourcesJar()) }
        val pomFile = dir.resolve("$module-$resolved.pom").apply { writeText(pomText(module, version), StandardCharsets.UTF_8) }
        val moduleFile = dir.resolve("$module-$resolved.module").apply { writeText(moduleMetadataText(module, version), StandardCharsets.UTF_8) }
        listOf(binaryFile, sourcesFile, pomFile, moduleFile, metadataFile).forEach(::writeChecksums)
    }

    private fun writeChecksums(file: File) {
        val bytes = file.readBytes()
        listOf("MD5" to "md5", "SHA-1" to "sha1", "SHA-256" to "sha256", "SHA-512" to "sha512").forEach { (alg, ext) ->
            file.resolveSibling("${file.name}.$ext").writeText(PublishedReleaseRepositoryVerifier.digest(alg, bytes), StandardCharsets.UTF_8)
        }
    }

    private fun rewriteChecksums(file: File) {
        file.resolveSibling("${file.name}.md5").delete()
        file.resolveSibling("${file.name}.sha1").delete()
        file.resolveSibling("${file.name}.sha256").delete()
        file.resolveSibling("${file.name}.sha512").delete()
        writeChecksums(file)
    }

    private fun validBinaryJar(
        module: String,
        version: String,
        classEntries: List<String> = listOf("ai/icen/$module/FileWeft.class"),
    ): ByteArray {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Implementation-Title", module)
            mainAttributes.putValue("Implementation-Version", version)
        }
        return ByteArrayOutputStream().use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                manifest.write(zip)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("META-INF/LICENSE"))
                zip.write(licenseBytes)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("META-INF/NOTICE"))
                zip.write(noticeBytes)
                zip.closeEntry()
                classEntries.forEach { classEntry ->
                    zip.putNextEntry(ZipEntry(classEntry))
                    zip.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
                    zip.closeEntry()
                }
            }
            stream.toByteArray()
        }
    }

    private fun validSourcesJar(): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/LICENSE"))
                zip.write(licenseBytes)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("META-INF/NOTICE"))
                zip.write(noticeBytes)
                zip.putNextEntry(ZipEntry("FileWeft.kt"))
                zip.write("class FileWeft".toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            stream.toByteArray()
        }
    }

    private fun jarWithPathTraversal(): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/LICENSE"))
                zip.write(licenseBytes)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("META-INF/NOTICE"))
                zip.write(noticeBytes)
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zip.write("Manifest-Version: 1.0\n".toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("../evil.class"))
                zip.write(byteArrayOf(0x00))
                zip.closeEntry()
            }
            stream.toByteArray()
        }
    }

    private fun pomText(module: String, version: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>ai.icen</groupId>
          <artifactId>$module</artifactId>
          <version>$version</version>
          <name>FlowWeft module $module</name>
          <description>FlowWeft enterprise file and workflow infrastructure module $module.</description>
          <url>$homepage</url>
          <licenses>
            <license>
              <name>$apacheLicenseName</name>
              <url>$apacheLicenseUrl</url>
              <distribution>repo</distribution>
            </license>
          </licenses>
          <developers>
            <developer>
              <id>icen.ai</id>
              <name>icen.ai</name>
              <organization>icen.ai</organization>
              <email>support@icen.ai</email>
            </developer>
          </developers>
          <scm>
            <connection>$scmConnection</connection>
            <developerConnection>$scmConnection</developerConnection>
            <url>$homepage</url>
            <tag>v$version</tag>
          </scm>
          <dependencies></dependencies>
        </project>
    """.trimIndent()

    private fun moduleMetadataText(module: String, version: String): String = """
        {
          "formatVersion": "1.1",
          "component": {
            "group": "ai.icen",
            "module": "$module",
            "version": "$version",
            "attributes": {}
          },
          "createdBy": {},
          "variants": []
        }
    """.trimIndent()

    private fun snapshotMetadataText(module: String, timestamp: String, buildNumber: String): String {
        val resolved = "0.0.2-$timestamp-$buildNumber"
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>ai.icen</groupId>
              <artifactId>$module</artifactId>
              <version>0.0.2-SNAPSHOT</version>
              <versioning>
                <snapshot>
                  <timestamp>$timestamp</timestamp>
                  <buildNumber>$buildNumber</buildNumber>
                </snapshot>
                <lastUpdated>${timestamp.replace(".", "")}</lastUpdated>
                <snapshotVersions>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <value>$resolved</value>
                    <updated>${timestamp.replace(".", "")}</updated>
                  </snapshotVersion>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <classifier>sources</classifier>
                    <value>$resolved</value>
                    <updated>${timestamp.replace(".", "")}</updated>
                  </snapshotVersion>
                  <snapshotVersion>
                    <extension>pom</extension>
                    <value>$resolved</value>
                    <updated>${timestamp.replace(".", "")}</updated>
                  </snapshotVersion>
                  <snapshotVersion>
                    <extension>module</extension>
                    <value>$resolved</value>
                    <updated>${timestamp.replace(".", "")}</updated>
                  </snapshotVersion>
                </snapshotVersions>
              </versioning>
            </metadata>
        """.trimIndent()
    }
}
