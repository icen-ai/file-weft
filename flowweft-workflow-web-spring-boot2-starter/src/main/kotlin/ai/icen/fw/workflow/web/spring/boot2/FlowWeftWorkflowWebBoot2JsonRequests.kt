package ai.icen.fw.workflow.web.spring.boot2

import ai.icen.fw.workflow.web.api.WorkflowCommentDocumentCommand
import ai.icen.fw.workflow.web.api.WorkflowCommentTokenCommand
import ai.icen.fw.workflow.web.api.WorkflowDefinitionDraftCommand
import ai.icen.fw.workflow.web.api.WorkflowDefinitionLifecycleCommand
import ai.icen.fw.workflow.web.api.WorkflowFormSubmissionCommand
import ai.icen.fw.workflow.web.api.WorkflowInstanceControlCommand
import ai.icen.fw.workflow.web.api.WorkflowInstanceStartCommand
import ai.icen.fw.workflow.web.api.WorkflowPrincipalTargetCommand
import ai.icen.fw.workflow.web.api.WorkflowSubjectDto
import ai.icen.fw.workflow.web.api.WorkflowTaskAddSignCommand
import ai.icen.fw.workflow.web.api.WorkflowTaskClaimCommand
import ai.icen.fw.workflow.web.api.WorkflowTaskDecisionCommand
import ai.icen.fw.workflow.web.api.WorkflowTaskDelegateCommand
import ai.icen.fw.workflow.web.api.WorkflowTaskReturnCommand

/** Mutable transport-only JSON shapes are converted immediately into validated immutable API DTOs. */
internal class WorkflowDefinitionDraftJson {
    var key: String? = null
    var version: String? = null
    var title: String? = null
    var codecId: String? = null
    var codecVersion: String? = null
    var definitionSource: String? = null
    var sourceDigest: String? = null
    var description: String? = null

    fun toCommand(): WorkflowDefinitionDraftCommand = WorkflowDefinitionDraftCommand(
        required(key), required(version), required(title), required(codecId), required(codecVersion),
        required(definitionSource), required(sourceDigest), description,
    )
}

internal class WorkflowLifecycleJson {
    var reasonCode: String? = null
    fun toCommand(): WorkflowDefinitionLifecycleCommand = WorkflowDefinitionLifecycleCommand(reasonCode)
}

internal class WorkflowSubjectJson {
    var type: String? = null
    var id: String? = null
    var revision: String? = null
    var digest: String? = null
    fun toDto(): WorkflowSubjectDto = WorkflowSubjectDto(required(type), required(id), required(revision), required(digest))
}

internal class WorkflowInstanceStartJson {
    var definitionKey: String? = null
    var definitionVersion: String? = null
    var subject: WorkflowSubjectJson? = null
    var canonicalInput: String? = null
    var inputDigest: String? = null
    fun toCommand(): WorkflowInstanceStartCommand = WorkflowInstanceStartCommand(
        required(definitionKey), required(definitionVersion), required(subject).toDto(), canonicalInput, inputDigest,
    )
}

internal class WorkflowInstanceControlJson {
    var reasonCode: String? = null
    var note: String? = null
    fun toCommand(): WorkflowInstanceControlCommand = WorkflowInstanceControlCommand(required(reasonCode), note)
}

internal class WorkflowTaskClaimJson {
    fun toCommand(): WorkflowTaskClaimCommand = WorkflowTaskClaimCommand()
}

internal class WorkflowPrincipalTargetJson {
    var type: String? = null
    var id: String? = null
    fun toCommand(): WorkflowPrincipalTargetCommand = WorkflowPrincipalTargetCommand(required(type), required(id))
}

internal class WorkflowCommentTokenJson {
    var kind: String? = null
    var text: String? = null
    var target: WorkflowPrincipalTargetJson? = null

    fun toCommand(): WorkflowCommentTokenCommand = when (required(kind)) {
        "TEXT" -> WorkflowCommentTokenCommand.text(required(text).also { require(target == null) })
        "MENTION" -> WorkflowCommentTokenCommand.mention(required(target).toCommand().also { require(text == null) })
        else -> throw IllegalArgumentException("Workflow comment token kind is unsupported.")
    }
}

internal class WorkflowCommentDocumentJson {
    var tokens: List<WorkflowCommentTokenJson>? = null
    fun toCommand(): WorkflowCommentDocumentCommand = WorkflowCommentDocumentCommand(
        required(tokens).map { it.toCommand() },
    )
}

internal class WorkflowTaskDecisionJson {
    var action: String? = null
    var comment: WorkflowCommentDocumentJson? = null
    var formSubmissionId: String? = null
    var formSubmissionDigest: String? = null
    fun toCommand(): WorkflowTaskDecisionCommand = WorkflowTaskDecisionCommand(
        required(action), comment?.toCommand(), formSubmissionId, formSubmissionDigest,
    )
}

internal class WorkflowTaskDelegateJson {
    var target: WorkflowPrincipalTargetJson? = null
    var mode: String? = null
    var reasonCode: String? = null
    fun toCommand(): WorkflowTaskDelegateCommand = WorkflowTaskDelegateCommand(
        required(target).toCommand(), required(mode), required(reasonCode),
    )
}

internal class WorkflowTaskAddSignJson {
    var targets: List<WorkflowPrincipalTargetJson>? = null
    var position: String? = null
    var reasonCode: String? = null
    fun toCommand(): WorkflowTaskAddSignCommand = WorkflowTaskAddSignCommand(
        required(targets).map { it.toCommand() }, required(position), required(reasonCode),
    )
}

internal class WorkflowTaskReturnJson {
    var targetNodeId: String? = null
    var mode: String? = null
    var reasonCode: String? = null
    fun toCommand(): WorkflowTaskReturnCommand = WorkflowTaskReturnCommand(
        required(targetNodeId), required(mode), required(reasonCode),
    )
}

internal class WorkflowFormSubmissionJson {
    var formId: String? = null
    var formVersion: String? = null
    var canonicalData: String? = null
    var dataDigest: String? = null
    fun toCommand(): WorkflowFormSubmissionCommand = WorkflowFormSubmissionCommand(
        required(formId), required(formVersion), required(canonicalData), required(dataDigest),
    )
}

private fun <T : Any> required(value: T?): T =
    value ?: throw IllegalArgumentException("Workflow JSON property is required.")
