package com.fileweft.web.api.v1.doctor

import com.fileweft.web.api.immutableList
import com.fileweft.web.api.optionalText
import com.fileweft.web.api.requiredText

/**
 * One redacted, operator-facing Doctor check result.
 *
 * Raw checker evidence is deliberately absent: it can contain storage paths,
 * downstream identifiers, exception text, or connector configuration. A
 * future explicitly authorized diagnostics endpoint may define a separately
 * reviewed allow-listed evidence contract.
 */
class DoctorCheckDto @JvmOverloads constructor(
    checkerName: String,
    status: String,
    reason: String,
    repairSuggestion: String? = null,
) {
    val checkerName: String = requiredText(checkerName, "Doctor checker name", 128)
    val status: String = requiredText(status, "Doctor check status", 64)
    val reason: String = requiredText(reason, "Doctor check reason", 512)
    val repairSuggestion: String? = optionalText(repairSuggestion, "Doctor repair suggestion", 1_000)
}

/** Immutable report DTO that does not expose a tenant identifier. */
class DoctorReportDto(
    documentId: String,
    status: String,
    checks: List<DoctorCheckDto>,
    val inspectedTime: Long,
) {
    val documentId: String = requiredText(documentId, "Doctor report document id", 128)
    val status: String = requiredText(status, "Doctor report status", 64)
    val checks: List<DoctorCheckDto> = immutableList(checks)

    init {
        require(inspectedTime >= 0) { "Doctor report inspection time must not be negative." }
        require(this.checks.map { it.checkerName }.distinct().size == this.checks.size) {
            "Doctor report checker names must be unique."
        }
    }
}

/** Current state of an asynchronously scheduled document diagnosis. */
class DoctorTaskDto(
    id: String,
    documentId: String,
    status: String,
    val createdTime: Long,
    val updatedTime: Long,
) {
    val id: String = requiredText(id, "Doctor task id", 128)
    val documentId: String = requiredText(documentId, "Doctor task document id", 128)
    val status: String = requiredText(status, "Doctor task status", 64)

    init {
        require(createdTime >= 0) { "Doctor task creation time must not be negative." }
        require(updatedTime >= createdTime) { "Doctor task update time must not precede creation time." }
    }
}

/** Stable receipt returned after a document Doctor task is durably scheduled. */
class DoctorTaskReceiptDto(
    taskId: String,
    documentId: String,
    status: String,
) {
    val taskId: String = requiredText(taskId, "Doctor task id", 128)
    val documentId: String = requiredText(documentId, "Doctor task document id", 128)
    val status: String = requiredText(status, "Doctor task status", 64)
}

/**
 * Current public task state and its optional completed, redacted report.
 *
 * Worker payload, retry details, lease ownership, raw error text, and stored
 * report JSON deliberately remain outside this contract.
 */
class DoctorTaskDetailDto @JvmOverloads constructor(
    val task: DoctorTaskDto,
    val report: DoctorReportDto? = null,
) {
    init {
        require(report == null || report.documentId == task.documentId) {
            "Doctor task report must belong to the task document."
        }
    }
}

/** Tenant-context-derived system diagnosis that exposes no tenant or document identifier. */
class SystemDoctorReportDto(
    status: String,
    checks: List<DoctorCheckDto>,
    val inspectedTime: Long,
) {
    val status: String = requiredText(status, "System Doctor report status", 64)
    val checks: List<DoctorCheckDto> = immutableList(checks)

    init {
        require(inspectedTime >= 0) { "System Doctor report inspection time must not be negative." }
        require(this.checks.map { it.checkerName }.distinct().size == this.checks.size) {
            "System Doctor report checker names must be unique."
        }
    }
}

/** Marker request for the document-scoped Doctor scheduling endpoint. */
class ScheduleDocumentDoctorCommand
