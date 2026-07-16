package ai.icen.fw.buildlogic

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublicationInventoryVerifierTest {

    @Test
    fun `accepts the checked in physical inventory contract`() {
        withCheckedInInventoryFixture { inventoryFile, repositoryRoot, entries ->
            val verified = PublicationInventoryVerifier(inventoryFile, repositoryRoot).verify()
            assertEquals(entries, verified)
            assertEquals(68, verified.size)
            assertEquals(19, verified.count { entry -> entry.lineage == PublicationInventoryVerifier.LEGACY_PHYSICAL })
            assertEquals(49, verified.count { entry -> entry.lineage == PublicationInventoryVerifier.NEW_PHYSICAL })
            assertTrue(verified.all { entry -> entry.artifactKind == PublicationInventoryVerifier.JAR_KIND })
            val artifacts = verified.associateBy { entry -> entry.artifactId }
            assertEquals(
                PublicationInventoryEntry(
                    artifactId = "flowweft-agent-testkit",
                    artifactKind = PublicationInventoryVerifier.JAR_KIND,
                    lineage = PublicationInventoryVerifier.NEW_PHYSICAL,
                    jvmBaseline = 8,
                ),
                artifacts["flowweft-agent-testkit"],
            )
            assertEquals(
                PublicationInventoryEntry(
                    artifactId = "flowweft-retrieval-testkit",
                    artifactKind = PublicationInventoryVerifier.JAR_KIND,
                    lineage = PublicationInventoryVerifier.NEW_PHYSICAL,
                    jvmBaseline = 8,
                ),
                artifacts["flowweft-retrieval-testkit"],
            )
        }
    }

    @Test
    fun `parser rejects duplicate artifact IDs`() {
        val text = """
            ${PublicationInventoryVerifier.HEADER}
            flowweft-example	jar	new-physical	8
            flowweft-example	jar	new-physical	8
        """.trimIndent()

        val failure = assertFailsWith<IllegalArgumentException> {
            PublicationInventoryVerifier.parse(text, "duplicate fixture")
        }
        assertTrue(failure.message.orEmpty().contains("duplicate artifact IDs"), failure.message)
    }

    @Test
    fun `parser rejects invalid bounded fields`() {
        val invalidRows = listOf(
            "../escape\tjar\tnew-physical\t8" to "artifact ID",
            "flowweft-example\tbom\tnew-physical\t8" to "artifact kind",
            "flowweft-example\tjar\talias\t8" to "lineage",
            "flowweft-example\tjar\tnew-physical\t11" to "JVM baseline",
        )
        invalidRows.forEach { (row, expectedMessage) ->
            val failure = assertFailsWith<IllegalArgumentException> {
                PublicationInventoryVerifier.parse(
                    "${PublicationInventoryVerifier.HEADER}\n$row\n",
                    "invalid field fixture",
                )
            }
            assertTrue(failure.message.orEmpty().contains(expectedMessage), failure.message)
        }
    }

    @Test
    fun `parser reserves a physical platform kind without treating it as a JAR`() {
        val entries = PublicationInventoryVerifier.parse(
            "${PublicationInventoryVerifier.HEADER}\nflowweft-platform\tplatform\tnew-physical\t17\n",
            "platform fixture",
        )

        assertEquals(PublicationInventoryVerifier.PLATFORM_KIND, entries.single().artifactKind)
    }

    @Test
    fun `rejects changes to the frozen legacy artifact set`() {
        val checkedIn = checkedInInventoryFile().readText(StandardCharsets.UTF_8)
        val modified = checkedIn.replace(
            "fileweft-core\tjar\tlegacy-physical\t8",
            "fileweft-core-renamed\tjar\tlegacy-physical\t8",
        )
        val fixtureRoot = Files.createTempDirectory("flowweft-inventory-legacy-drift-").toFile()
        try {
            val inventoryFile = fixtureRoot.resolve("inventory.tsv").apply {
                writeText(modified, StandardCharsets.UTF_8)
            }
            val failure = assertFailsWith<IllegalArgumentException> {
                PublicationInventoryVerifier(inventoryFile, fixtureRoot).verify()
            }
            assertTrue(failure.message.orEmpty().contains("frozen legacy"), failure.message)
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    @Test
    fun `rejects a new physical artifact using the legacy prefix`() {
        val checkedIn = checkedInInventoryFile().readText(StandardCharsets.UTF_8)
        val modified = checkedIn.replace(
            "flowweft-agent-api\tjar\tnew-physical\t8",
            "fileweft-agent-api-new\tjar\tnew-physical\t8",
        )
        val fixtureRoot = Files.createTempDirectory("flowweft-inventory-new-prefix-").toFile()
        try {
            val inventoryFile = fixtureRoot.resolve("inventory.tsv").apply {
                writeText(modified, StandardCharsets.UTF_8)
            }
            val failure = assertFailsWith<IllegalArgumentException> {
                PublicationInventoryVerifier(inventoryFile, fixtureRoot).verify()
            }
            assertTrue(failure.message.orEmpty().contains("flowweft-*"), failure.message)
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    @Test
    fun `rejects a missing physical module directory`() {
        withCheckedInInventoryFixture { inventoryFile, repositoryRoot, _ ->
            repositoryRoot.resolve("fileweft-core").deleteRecursively()
            val failure = assertFailsWith<IllegalArgumentException> {
                PublicationInventoryVerifier(inventoryFile, repositoryRoot).verify()
            }
            assertTrue(failure.message.orEmpty().contains("module directory is missing"), failure.message)
        }
    }

    @Test
    fun `rejects a JAR baseline that disagrees with its convention plugin`() {
        withCheckedInInventoryFixture { inventoryFile, repositoryRoot, _ ->
            repositoryRoot.resolve("fileweft-core/build.gradle.kts")
                .writeText("plugins { id(\"fileweft.jvm17-library\") }\n", StandardCharsets.UTF_8)
            val failure = assertFailsWith<IllegalArgumentException> {
                PublicationInventoryVerifier(inventoryFile, repositoryRoot).verify()
            }
            assertTrue(failure.message.orEmpty().contains("JVM baseline does not match"), failure.message)
        }
    }

    private fun withCheckedInInventoryFixture(
        action: (File, File, List<PublicationInventoryEntry>) -> Unit,
    ) {
        val sourceInventory = checkedInInventoryFile()
        val entries = PublicationInventoryVerifier.parse(sourceInventory)
        val fixtureRoot = Files.createTempDirectory("flowweft-publication-inventory-").toFile()
        try {
            val fixtureInventory = fixtureRoot.resolve("publication-inventory.tsv").apply {
                writeText(sourceInventory.readText(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
            }
            entries.forEach { entry ->
                val moduleDirectory = fixtureRoot.resolve(entry.artifactId).apply { mkdirs() }
                val buildText = if (entry.artifactKind == PublicationInventoryVerifier.JAR_KIND) {
                    "plugins { id(\"fileweft.jvm${entry.jvmBaseline}-library\") }\n"
                } else {
                    "plugins { base }\n"
                }
                moduleDirectory.resolve("build.gradle.kts").writeText(buildText, StandardCharsets.UTF_8)
            }
            action(fixtureInventory, fixtureRoot, entries)
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    private fun checkedInInventoryFile(): File = sequenceOf(
        File("../gradle/publication-inventory.tsv"),
        File("gradle/publication-inventory.tsv"),
    ).map { candidate -> candidate.absoluteFile.normalize() }
        .firstOrNull { candidate -> candidate.isFile }
        ?: error("Cannot locate the checked-in gradle/publication-inventory.tsv fixture.")
}
