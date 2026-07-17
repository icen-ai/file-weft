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
        val deprecatedSymbols = deprecatedSymbolKeys(baseline)
        baseline.records.forEach { oldRecord ->
            if (isLegacyIgnoredRecord(oldRecord) || oldRecord.symbolKey in deprecatedSymbols) return@forEach
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
        if (!interfacesCompatible(old, candidate)) return false
        return compatibleAccessFlags(old, candidate, CLASS_EXACT_ACCESS_FLAGS, CLASS_FLAGS_MUST_NOT_BE_ADDED)
    }

    private fun interfacesCompatible(old: JvmApiRecord, candidate: JvmApiRecord): Boolean {
        val oldInterfaces = old.attributes["interfaces"].orEmpty().split(',').filter(String::isNotEmpty).toSet()
        val candidateInterfaces = candidate.attributes["interfaces"].orEmpty()
            .split(',')
            .filter(String::isNotEmpty)
            .toSet()
        return if (old.attributes["kind"] == "class") {
            // A class may implement extra interfaces without invalidating callers compiled
            // against the released class; losing a released interface remains incompatible.
            candidateInterfaces.containsAll(oldInterfaces)
        } else {
            candidateInterfaces == oldInterfaces
        }
    }

    private fun memberCompatible(
        old: JvmApiRecord,
        candidate: JvmApiRecord,
        exactFlags: Set<String>,
    ): Boolean {
        if (!visibilityCompatible(old, candidate)) return false
        if (!compatibleAccessFlags(old, candidate, exactFlags, MEMBER_FLAGS_MUST_NOT_BE_ADDED)) return false
        val ignoredAttributes = if (old.kind == JvmApiSnapshot.FIELD) setOf("access", "constant") else setOf("access")
        return old.attributes.filterKeys { key -> key !in ignoredAttributes } ==
            candidate.attributes.filterKeys { key -> key !in ignoredAttributes }
    }

    private fun visibilityCompatible(old: JvmApiRecord, candidate: JvmApiRecord): Boolean {
        val oldVisibility = visibilityRank(accessFlags(old))
        val candidateVisibility = visibilityRank(accessFlags(candidate))
        return candidateVisibility >= oldVisibility
    }

    private fun compatibleAccessFlags(
        old: JvmApiRecord,
        candidate: JvmApiRecord,
        exactFlags: Set<String>,
        flagsThatMustNotBeAdded: Set<String>,
    ): Boolean {
        val oldFlags = accessFlags(old)
        val candidateFlags = accessFlags(candidate)
        return oldFlags.intersect(exactFlags) == candidateFlags.intersect(exactFlags) &&
            flagsThatMustNotBeAdded.none { flag -> flag in candidateFlags && flag !in oldFlags }
    }

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

    private fun isLegacyIgnoredRecord(record: JvmApiRecord): Boolean =
        record.kind == JvmApiSnapshot.ANNOTATION ||
            isSyntheticBytecodeMember(record) ||
            isKotlinInlineImplementation(record)

    private fun deprecatedSymbolKeys(baseline: JvmApiSnapshot): Set<String> = baseline.records.asSequence()
        .filter { record ->
            record.kind == JvmApiSnapshot.ANNOTATION && record.descriptor == JAVA_DEPRECATED_DESCRIPTOR
        }
        .mapNotNull(::deprecatedSymbolKey)
        .toSet()

    private fun deprecatedSymbolKey(annotation: JvmApiRecord): String? = when {
        annotation.name == "CLASS" -> symbolKey(JvmApiSnapshot.CLASS, annotation.owner, "", "")
        annotation.name.startsWith("FIELD:") -> memberSymbolKey(annotation, JvmApiSnapshot.FIELD)
        annotation.name.startsWith("METHOD:") -> memberSymbolKey(annotation, JvmApiSnapshot.METHOD)
        annotation.name.startsWith("RECORD_COMPONENT:") -> memberSymbolKey(annotation, JvmApiSnapshot.RECORD_COMPONENT)
        else -> null
    }

    private fun memberSymbolKey(annotation: JvmApiRecord, kind: String): String? {
        val member = annotation.name.substringAfter(':')
        val separator = member.indexOf(':')
        if (separator <= 0) return null
        return symbolKey(kind, annotation.owner, member.substring(0, separator), member.substring(separator + 1))
    }

    private fun symbolKey(kind: String, owner: String, name: String, descriptor: String): String =
        listOf(kind, owner, name, descriptor).joinToString("\u0000")

    private fun isSyntheticBytecodeMember(record: JvmApiRecord): Boolean =
        record.kind in setOf(JvmApiSnapshot.CLASS, JvmApiSnapshot.FIELD, JvmApiSnapshot.METHOD) &&
            "synthetic" in accessFlags(record)

    private fun isKotlinInlineImplementation(record: JvmApiRecord): Boolean =
        record.owner.contains(KOTLIN_INLINE_IMPLEMENTATION_MARKER)

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
        "interface",
        "annotation",
        "enum",
        "record",
    )
    private val CLASS_FLAGS_MUST_NOT_BE_ADDED = setOf("final", "abstract")
    private val FIELD_EXACT_ACCESS_FLAGS = setOf("static", "volatile", "transient", "enum")
    private val METHOD_EXACT_ACCESS_FLAGS = setOf(
        "static",
        "synchronized",
        "bridge",
        "varargs",
        "native",
        "strict",
    )
    private val MEMBER_FLAGS_MUST_NOT_BE_ADDED = setOf("final", "abstract")
    private const val JAVA_DEPRECATED_DESCRIPTOR = "Ljava/lang/Deprecated;"
    private const val KOTLIN_INLINE_IMPLEMENTATION_MARKER = "\$\$inlined\$"
    private const val MAX_DIFF_SAMPLE = 20
}
