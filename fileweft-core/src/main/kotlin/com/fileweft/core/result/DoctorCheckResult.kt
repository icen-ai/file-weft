package com.fileweft.core.result

import java.util.Collections
import java.util.LinkedHashMap

enum class DoctorStatus {
    HEALTHY,
    WARNING,
    ERROR,
    SKIPPED,
}

/** One component-level diagnostic result with operator-facing repair context. */
class DoctorCheckResult @JvmOverloads constructor(
    val checkerName: String,
    val status: DoctorStatus,
    reason: String,
    evidence: Map<String, String> = emptyMap(),
    val repairSuggestion: String? = null,
) {
    val reason: String = reason.also {
        require(it.isNotBlank()) { "Doctor check reason must not be blank." }
    }

    val evidence: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(evidence))

    init {
        require(checkerName.isNotBlank()) { "Doctor checker name must not be blank." }
        require(repairSuggestion == null || repairSuggestion.isNotBlank()) {
            "Doctor repair suggestion must not be blank when provided."
        }
    }
}
