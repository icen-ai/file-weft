package ai.icen.fw.buildlogic

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmApiCompatibilityVerifierTest {

    @Test
    fun `legacy gate rejects removals visibility narrowing and incompatible flags`() {
        val baseline = snapshot(
            "0.0.3",
            classRecord(access = "public,super"),
            fieldRecord("REMOVED", access = "public,static,final"),
            fieldRecord("FLAG_CHANGED", access = "public,static,final"),
            methodRecord("work", access = "public"),
        )
        val candidate = snapshot(
            "1.0.0",
            classRecord(access = "public,super,final"),
            fieldRecord("FLAG_CHANGED", access = "public,static"),
            methodRecord("work", access = "protected"),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            JvmApiCompatibilityVerifier.verifyLegacy(ARTIFACT, listOf(baseline), candidate)
        }

        assertTrue(failure.message.orEmpty().contains("removed field example.Api.REMOVED"), failure.message)
        assertTrue(failure.message.orEmpty().contains("changed class example.Api"), failure.message)
        assertTrue(failure.message.orEmpty().contains("changed field example.Api.FLAG_CHANGED"), failure.message)
        assertTrue(failure.message.orEmpty().contains("changed method example.Api.work"), failure.message)
    }

    @Test
    fun `legacy gate rejects a new abstract method on a released interface`() {
        val releasedInterface = snapshot(
            "0.0.1",
            classRecord(access = "public,interface,abstract", kind = "interface"),
        )
        val candidate = snapshot(
            "1.0.0",
            classRecord(access = "public,interface,abstract", kind = "interface"),
            methodRecord("newRequirement", access = "public,abstract"),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            JvmApiCompatibilityVerifier.verifyLegacy(ARTIFACT, listOf(releasedInterface), candidate)
        }

        assertTrue(failure.message.orEmpty().contains("added abstract method"), failure.message)
        assertTrue(failure.message.orEmpty().contains("newRequirement"), failure.message)
    }

    @Test
    fun `legacy gate ignores raw Kotlin metadata encoding noise but checks stable metadata`() {
        val baseline = snapshot(
            "0.0.2",
            classRecord(),
            rawKotlinMetadata("compiler-payload-a"),
            stableKotlinMetadata(kind = "int:\"1\""),
        )
        val compilerNoiseOnly = snapshot(
            "1.0.0",
            classRecord(),
            rawKotlinMetadata("compiler-payload-b"),
            stableKotlinMetadata(kind = "int:\"1\""),
        )

        JvmApiCompatibilityVerifier.verifyLegacy(ARTIFACT, listOf(baseline), compilerNoiseOnly)

        val semanticMetadataChange = snapshot(
            "1.0.0",
            classRecord(),
            rawKotlinMetadata("compiler-payload-b"),
            stableKotlinMetadata(kind = "int:\"2\""),
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            JvmApiCompatibilityVerifier.verifyLegacy(ARTIFACT, listOf(baseline), semanticMetadataChange)
        }
        assertTrue(failure.message.orEmpty().contains("kotlin_metadata"), failure.message)
    }

    @Test
    fun `exact 1_0 freeze rejects an accidental public class`() {
        val baseline = snapshot("1.0.0", classRecord())
        val candidate = snapshot(
            "1.0.0",
            classRecord(),
            classRecord(owner = "example.Leaked"),
        )
        val exports = JvmApiExports(ARTIFACT, JvmApiBaselineState.READY, setOf(OWNER))

        val failure = assertFailsWith<IllegalArgumentException> {
            JvmApiCompatibilityVerifier.verifyExactNew(ARTIFACT, baseline, candidate, exports)
        }

        assertTrue(failure.message.orEmpty().contains("accidentalPublic=[example.Leaked]"), failure.message)
    }

    @Test
    fun `exact 1_0 freeze includes raw Kotlin metadata`() {
        val baseline = snapshot("1.0.0", classRecord(), rawKotlinMetadata("payload-a"))
        val candidate = snapshot("1.0.0", classRecord(), rawKotlinMetadata("payload-b"))
        val exports = JvmApiExports(ARTIFACT, JvmApiBaselineState.READY, setOf(OWNER))

        val failure = assertFailsWith<IllegalArgumentException> {
            JvmApiCompatibilityVerifier.verifyExactNew(ARTIFACT, baseline, candidate, exports)
        }

        assertTrue(failure.message.orEmpty().contains("Exact FlowWeft 1.0 JVM API freeze failed"), failure.message)
    }

    private fun snapshot(version: String, vararg records: JvmApiRecord): JvmApiSnapshot =
        JvmApiSnapshot(ARTIFACT, version, records.sorted())

    private fun classRecord(
        owner: String = OWNER,
        access: String = "public,super",
        kind: String = "class",
    ): JvmApiRecord = JvmApiRecord(
        JvmApiSnapshot.CLASS,
        owner,
        "",
        "",
        mapOf("access" to access, "kind" to kind, "super" to "java.lang.Object"),
    )

    private fun fieldRecord(name: String, access: String): JvmApiRecord = JvmApiRecord(
        JvmApiSnapshot.FIELD,
        OWNER,
        name,
        "I",
        mapOf("access" to access, "constant" to "int:\"1\""),
    )

    private fun methodRecord(name: String, access: String): JvmApiRecord = JvmApiRecord(
        JvmApiSnapshot.METHOD,
        OWNER,
        name,
        "()V",
        mapOf("access" to access),
    )

    private fun rawKotlinMetadata(payload: String): JvmApiRecord = JvmApiRecord(
        JvmApiSnapshot.ANNOTATION,
        OWNER,
        "CLASS",
        "Lkotlin/Metadata;",
        mapOf("visible" to "true", "values" to payload),
    )

    private fun stableKotlinMetadata(kind: String): JvmApiRecord = JvmApiRecord(
        JvmApiSnapshot.KOTLIN_METADATA,
        OWNER,
        "",
        "Lkotlin/Metadata;",
        mapOf("kind" to kind),
    )

    private companion object {
        const val ARTIFACT = "flowweft-example"
        const val OWNER = "example.Api"
    }
}
