package ai.icen.fw.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmApiBaselineInventoryTest {

    @Test
    fun `checked in inventory covers every reviewed release coordinate`() {
        val entries = JvmApiBaselineInventory.parse(checkedIn("gradle/compatibility/jvm-api-baselines.tsv"))
        val publications = PublicationInventoryVerifier.parse(checkedIn("gradle/publication-inventory.tsv"))

        JvmApiBaselineInventory.verifyCoverage(entries, publications)
        JvmApiBaselineInventory.verifyCoverage(
            entries,
            publications + PublicationInventoryEntry(
                "flowweft-platform",
                PublicationInventoryVerifier.PLATFORM_KIND,
                PublicationInventoryVerifier.NEW_PHYSICAL,
                17,
            ),
        )

        val legacyArtifacts = publications.count {
            entry -> entry.lineage == PublicationInventoryVerifier.LEGACY_PHYSICAL
        }
        val preMetadataLegacyArtifacts = publications.count { entry ->
            entry.lineage == PublicationInventoryVerifier.LEGACY_PHYSICAL &&
                entry.artifactId !in setOf("fileweft-metadata-api", "fileweft-metadata-runtime")
        }
        val newArtifacts = publications.count {
            entry -> entry.lineage == PublicationInventoryVerifier.NEW_PHYSICAL
        }
        assertEquals(preMetadataLegacyArtifacts * 2 + legacyArtifacts + newArtifacts, entries.size)
        assertEquals(setOf("0.0.1", "0.0.2", "0.0.3", "1.0.0"), entries.map { it.version }.toSet())
        assertTrue(entries.filter { it.version == "1.0.0" }.all { it.state == JvmApiBaselineState.PENDING })
        assertTrue(entries.filter { it.version != "1.0.0" }.all { it.state == JvmApiBaselineState.READY })
    }

    @Test
    fun `parser rejects traversal and unbounded provenance fields`() {
        val maliciousRows = listOf(
            row(artifactId = "../escape") to "artifact ID",
            row(sourceRef = "v../../escape") to "source ref",
            row(sourceCommit = "../../outside") to "source commit",
            row(jarSha256 = "A".repeat(64)) to "JAR SHA-256",
            row(apiSha256 = "A".repeat(64)) to "API SHA-256",
            row(artifactId = "a".repeat(81)) to "artifact ID",
        )

        maliciousRows.forEach { (maliciousRow, expectedMessage) ->
            val failure = assertFailsWith<IllegalArgumentException> {
                JvmApiBaselineInventory.parse(
                    "${JvmApiBaselineInventory.HEADER}\n$maliciousRow\n",
                    "malicious baseline fixture",
                )
            }
            assertTrue(failure.message.orEmpty().contains(expectedMessage), failure.message)
        }
    }

    @Test
    fun `ready row requires immutable commit jar and api digests`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            JvmApiBaselineInventory.parse(
                "${JvmApiBaselineInventory.HEADER}\n${row(state = "ready")}\n",
                "untrusted ready fixture",
            )
        }

        assertTrue(failure.message.orEmpty().contains("ready but lacks"), failure.message)
    }

    @Test
    fun `new ready row requires export digest and legacy row requires NONE`() {
        val digest = "a".repeat(64)
        val newReady = listOf(
            "1.0.0",
            "flowweft-agent-api",
            PublicationInventoryVerifier.NEW_PHYSICAL,
            "v1.0.0",
            "b".repeat(40),
            digest,
            digest,
            "PENDING",
            "ready",
        ).joinToString("\t")
        val newFailure = assertFailsWith<IllegalArgumentException> {
            JvmApiBaselineInventory.parse(
                "${JvmApiBaselineInventory.HEADER}\n$newReady\n",
                "new ready fixture",
            )
        }
        assertTrue(newFailure.message.orEmpty().contains("exports SHA-256"), newFailure.message)

        val legacyFailure = assertFailsWith<IllegalArgumentException> {
            JvmApiBaselineInventory.parse(
                "${JvmApiBaselineInventory.HEADER}\n${row(exportsSha256 = digest)}\n",
                "legacy exports fixture",
            )
        }
        assertTrue(legacyFailure.message.orEmpty().contains("must use NONE"), legacyFailure.message)
    }

    @Test
    fun `parser rejects duplicates and non canonical ordering`() {
        val duplicate = row()
        val duplicateFailure = assertFailsWith<IllegalArgumentException> {
            JvmApiBaselineInventory.parse(
                "${JvmApiBaselineInventory.HEADER}\n$duplicate\n$duplicate\n",
                "duplicate fixture",
            )
        }
        assertTrue(duplicateFailure.message.orEmpty().contains("duplicate"), duplicateFailure.message)

        val orderingFailure = assertFailsWith<IllegalArgumentException> {
            JvmApiBaselineInventory.parse(
                buildString {
                    append(JvmApiBaselineInventory.HEADER).append('\n')
                    append(row(artifactId = "fileweft-spi")).append('\n')
                    append(row(artifactId = "fileweft-core")).append('\n')
                },
                "unsorted fixture",
            )
        }
        assertTrue(orderingFailure.message.orEmpty().contains("sorted"), orderingFailure.message)
    }

    @Test
    fun `coverage rejects a silently omitted release coordinate`() {
        val entries = JvmApiBaselineInventory.parse(checkedIn("gradle/compatibility/jvm-api-baselines.tsv"))
        val publications = PublicationInventoryVerifier.parse(checkedIn("gradle/publication-inventory.tsv"))

        val failure = assertFailsWith<IllegalArgumentException> {
            JvmApiBaselineInventory.verifyCoverage(entries.dropLast(1), publications)
        }

        assertTrue(failure.message.orEmpty().contains("missing"), failure.message)
    }

    private fun row(
        artifactId: String = "fileweft-core",
        sourceRef: String = "v0.0.1-ai.icen",
        sourceCommit: String = "PENDING",
        jarSha256: String = "PENDING",
        apiSha256: String = "PENDING",
        exportsSha256: String = JvmApiBaselineInventory.NO_EXPORTS,
        state: String = "pending",
    ): String = listOf(
        "0.0.1",
        artifactId,
        PublicationInventoryVerifier.LEGACY_PHYSICAL,
        sourceRef,
        sourceCommit,
        jarSha256,
        apiSha256,
        exportsSha256,
        state,
    ).joinToString("\t")

    private fun checkedIn(path: String): File = sequenceOf(
        File("../$path"),
        File(path),
    ).map { candidate -> candidate.absoluteFile.normalize() }
        .firstOrNull { candidate -> candidate.isFile }
        ?: error("Cannot locate checked-in fixture $path.")
}
