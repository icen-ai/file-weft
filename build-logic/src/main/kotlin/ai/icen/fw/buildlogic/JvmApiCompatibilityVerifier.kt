package ai.icen.fw.buildlogic

/** Semantic checks shared by the release tasks and focused malicious-fixture tests. */
object JvmApiCompatibilityVerifier {
    @JvmStatic
    fun verifyLegacy(
        artifactId: String,
        historicalSnapshots: List<JvmApiSnapshot>,
        candidate: JvmApiSnapshot,
    ) {
        require(historicalSnapshots.isNotEmpty()) {
            "Legacy JVM ABI verification has no trusted baseline for $artifactId."
        }
        require(historicalSnapshots.all { snapshot -> snapshot.artifactId == artifactId }) {
            "Legacy JVM ABI baselines are bound to the wrong artifact for $artifactId."
        }
        require(candidate.artifactId == artifactId) {
            "Legacy JVM ABI candidate is bound to ${candidate.artifactId}, expected $artifactId."
        }

        val violations = linkedSetOf<String>()
        historicalSnapshots.sortedBy { snapshot -> snapshot.baselineVersion }.forEach { baseline ->
            compareOneLegacyBaseline(baseline, candidate, violations)
        }
        rejectNewAbstractInterfaceMethods(historicalSnapshots, candidate, violations)
        require(violations.isEmpty()) {
            "Legacy JVM ABI compatibility failed for $artifactId:\n" +
                violations.sorted().joinToString("\n") { violation -> " - $violation" }
        }
    }

    @JvmStatic
    fun verifyExactNew(
        artifactId: String,
        baseline: JvmApiSnapshot,
        candidate: JvmApiSnapshot,
        exports: JvmApiExports,
    ) {
        require(baseline.artifactId == artifactId && candidate.artifactId == artifactId) {
            "Exact JVM API baseline/candidate is bound to the wrong artifact for $artifactId."
        }
        require(exports.artifactId == artifactId) {
            "JVM API export manifest is bound to ${exports.artifactId}, expected $artifactId."
        }
        require(exports.state == JvmApiBaselineState.READY) {
            "JVM API export manifest for $artifactId is pending review."
        }
        require(baseline.baselineVersion == JvmApiBaselineInventory.NEW_BASELINE_VERSION) {
            "New FlowWeft JVM API baseline for $artifactId must be version " +
                "${JvmApiBaselineInventory.NEW_BASELINE_VERSION}, but was ${baseline.baselineVersion}."
        }
        require(baseline.publicClassNames == exports.classNames) {
            "Reviewed exports and the 1.0 baseline differ for $artifactId; " +
                "missingFromBaseline=${exports.classNames - baseline.publicClassNames}, " +
                "unexportedBaselineClasses=${baseline.publicClassNames - exports.classNames}."
        }
        require(candidate.publicClassNames == exports.classNames) {
            "Candidate public classes differ from the explicit 1.0 exports for $artifactId; " +
                "missing=${exports.classNames - candidate.publicClassNames}, " +
                "accidentalPublic=${candidate.publicClassNames - exports.classNames}."
        }
        require(candidate.records == baseline.records) {
            val baselineLines = baseline.records.map(JvmApiRecord::render).toSet()
            val candidateLines = candidate.records.map(JvmApiRecord::render).toSet()
            "Exact FlowWeft 1.0 JVM API freeze failed for $artifactId; " +
                "missing=${(baselineLines - candidateLines).sorted().take(MAX_DIFF_SAMPLE)}, " +
                "addedOrChanged=${(candidateLines - baselineLines).sorted().take(MAX_DIFF_SAMPLE)}."
        }
    }

    private fun compareOneLegacyBaseline(
        baseline: JvmApiSnapshot,
        candidate: JvmApiSnapshot,
        violations: MutableSet<String>,
    ) {
        val candidateBySymbol = candidate.records.groupBy(JvmApiRecord::symbolKey)
        baseline.records.forEach { oldRecord ->
            if (isRawKotlinMetadataAnnotation(oldRecord)) return@forEach
            val matches = candidateBySymbol[oldRecord.symbolKey].orEmpty()
            if (matches.isEmpty()) {
                violations += "${baseline.baselineVersion}: removed ${display(oldRecord)}"
                return@forEach
            }
            val candidateRecord = matches.singleOrNull { record -> recordsCompatible(oldRecord, record) }
            if (candidateRecord == null) {
                violations += "${baseline.baselineVersion}: changed ${display(oldRecord)}; " +
                    "expected=${oldRecord.attributes}, actual=${matches.map { it.attributes }}"
            }
        }
    }

    private fun recordsCompatible(old: JvmApiRecord, candidate: JvmApiRecord): Boolean {
        if (old.kind != candidate.kind || old.owner != candidate.owner || old.name != candidate.name ||
            old.descriptor != candidate.descriptor
        ) {
            return false
        }
        return when (old.kind) {
            JvmApiSnapshot.CLASS -> classCompatible(old, candidate)
            JvmApiSnapshot.FIELD -> memberCompatible(old, candidate, FIELD_EXACT_ACCESS_FLAGS)
            JvmApiSnapshot.METHOD -> memberCompatible(old, candidate, METHOD_EXACT_ACCESS_FLAGS)
            JvmApiSnapshot.KOTLIN_METADATA -> old.attributes == candidate.attributes
            else -> old.attributes == candidate.attributes
        }
    }

    private fun classCompatible(old: JvmApiRecord, candidate: JvmApiRecord): Boolean {
        if (!visibilityCompatible(old, candidate)) return false
        if (old.attributes["kind"] != candidate.attributes["kind"]) return false
        if (old.attributes["signature"] != candidate.attributes["signature"]) return false
        if (old.attributes["super"] != candidate.attributes["super"]) return false
        if (old.attributes["interfaces"] != candidate.attributes["interfaces"]) return false
        return exactAccessFlags(old, candidate, CLASS_EXACT_ACCESS_FLAGS)
    }

    private fun memberCompatible(
        old: JvmApiRecord,
        candidate: JvmApiRecord,
        exactFlags: Set<String>,
    ): Boolean {
        if (!visibilityCompatible(old, candidate)) return false
        if (!exactAccessFlags(old, candidate, exactFlags)) return false
        return old.attributes.filterKeys { key -> key != "access" } ==
            candidate.attributes.filterKeys { key -> key != "access" }
    }

    private fun visibilityCompatible(old: JvmApiRecord, candidate: JvmApiRecord): Boolean {
        val oldVisibility = visibilityRank(accessFlags(old))
        val candidateVisibility = visibilityRank(accessFlags(candidate))
        return candidateVisibility >= oldVisibility
    }

    private fun exactAccessFlags(
        old: JvmApiRecord,
        candidate: JvmApiRecord,
        exactFlags: Set<String>,
    ): Boolean = accessFlags(old).intersect(exactFlags) == accessFlags(candidate).intersect(exactFlags)

    private fun rejectNewAbstractInterfaceMethods(
        historicalSnapshots: List<JvmApiSnapshot>,
        candidate: JvmApiSnapshot,
        violations: MutableSet<String>,
    ) {
        val historicalRecords = historicalSnapshots.flatMap { snapshot -> snapshot.records }
        val historicalInterfaces = historicalRecords.asSequence()
            .filter { record ->
                record.kind == JvmApiSnapshot.CLASS && record.attributes["kind"] in setOf("interface", "annotation")
            }
            .map { record -> record.owner }
            .toSet()
        val historicalMethodKeys = historicalRecords.asSequence()
            .filter { record -> record.kind == JvmApiSnapshot.METHOD }
            .map(JvmApiRecord::symbolKey)
            .toSet()
        candidate.records.asSequence()
            .filter { record -> record.kind == JvmApiSnapshot.METHOD && record.owner in historicalInterfaces }
            .filter { record -> record.symbolKey !in historicalMethodKeys }
            .filter { record -> "abstract" in accessFlags(record) && "static" !in accessFlags(record) }
            .forEach { method ->
                violations += "candidate added abstract method to released interface ${display(method)}"
            }
    }

    private fun isRawKotlinMetadataAnnotation(record: JvmApiRecord): Boolean =
        record.kind == JvmApiSnapshot.ANNOTATION && record.descriptor == KOTLIN_METADATA_DESCRIPTOR

    private fun accessFlags(record: JvmApiRecord): Set<String> = record.attributes["access"]
        .orEmpty()
        .split(',')
        .filter(String::isNotEmpty)
        .toSet()

    private fun visibilityRank(flags: Set<String>): Int = when {
        "public" in flags -> 2
        "protected" in flags -> 1
        else -> 0
    }

    private fun display(record: JvmApiRecord): String = when (record.kind) {
        JvmApiSnapshot.CLASS -> "class ${record.owner}"
        JvmApiSnapshot.FIELD -> "field ${record.owner}.${record.name}:${record.descriptor}"
        JvmApiSnapshot.METHOD -> "method ${record.owner}.${record.name}${record.descriptor}"
        else -> "${record.kind.lowercase()} ${record.owner} ${record.name} ${record.descriptor}"
    }

    private val CLASS_EXACT_ACCESS_FLAGS = setOf(
        "static",
        "final",
        "interface",
        "abstract",
        "annotation",
        "enum",
        "record",
    )
    private val FIELD_EXACT_ACCESS_FLAGS = setOf("static", "final", "volatile", "transient", "enum")
    private val METHOD_EXACT_ACCESS_FLAGS = setOf(
        "static",
        "final",
        "abstract",
        "synchronized",
        "bridge",
        "varargs",
        "native",
        "strict",
    )
    private const val KOTLIN_METADATA_DESCRIPTOR = "Lkotlin/Metadata;"
    private const val MAX_DIFF_SAMPLE = 20
}
