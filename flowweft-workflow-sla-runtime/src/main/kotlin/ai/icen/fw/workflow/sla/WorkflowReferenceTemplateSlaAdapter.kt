package ai.icen.fw.workflow.sla

import ai.icen.fw.workflow.api.WorkflowNodeKind
import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarProfile
import ai.icen.fw.workflow.spi.WorkflowBusinessCalendarRef
import ai.icen.fw.workflow.templates.WorkflowReferenceTemplate
import ai.icen.fw.workflow.templates.WorkflowTemplateProfileKind
import ai.icen.fw.workflow.templates.WorkflowTemplateSlaMetadata

/** Strict bridge from reference-template metadata to executable typed policy. */
object WorkflowReferenceTemplateSlaAdapter {
    @JvmStatic
    fun policies(
        template: WorkflowReferenceTemplate,
        calendar: WorkflowBusinessCalendarRef,
        providerProfile: WorkflowBusinessCalendarProfile,
        actionProfile: WorkflowSlaActionProfile,
    ): List<WorkflowSlaPolicy> = WorkflowSlaSupport.immutable(
        template.slaMetadata.map { metadata ->
            policy(template, metadata, calendar, providerProfile, actionProfile)
        },
        WorkflowSlaSupport.MAX_ITEMS,
        "Workflow reference-template SLA policies are invalid or exceed the limit.",
    )

    @JvmStatic
    fun policy(
        template: WorkflowReferenceTemplate,
        metadata: WorkflowTemplateSlaMetadata,
        calendar: WorkflowBusinessCalendarRef,
        providerProfile: WorkflowBusinessCalendarProfile,
        actionProfile: WorkflowSlaActionProfile,
    ): WorkflowSlaPolicy {
        require(template.slaMetadata.any { it.contentDigest == metadata.contentDigest }) {
            "Workflow SLA metadata does not belong to the exact reference template."
        }
        require(metadata.calendarProfile.kind == WorkflowTemplateProfileKind.BUSINESS_CALENDAR) {
            "Workflow SLA metadata requires a business-calendar profile."
        }
        val node = template.definition.nodes.firstOrNull { it.nodeId == metadata.nodeId }
            ?: throw IllegalArgumentException("Workflow SLA metadata references a missing node.")
        require(node.kind == WorkflowNodeKind.HUMAN_TASK) {
            "Workflow SLA metadata can schedule only a human task."
        }
        val calendarBinding = WorkflowSlaCalendarBinding.of(
            metadata.calendarProfile.profileId,
            metadata.calendarProfile.version,
            metadata.calendarProfile.digest,
            metadata.calendarProfile.bindingDigest,
            calendar,
            providerProfile,
        )
        val policyId = WorkflowSlaSupport.digest("flowweft-workflow-sla-template-policy-id-v1")
            .text(template.templateId)
            .text(template.templateVersion)
            .text(metadata.nodeId)
            .text(metadata.contentDigest)
            .finish()
        return WorkflowSlaPolicy.standard(
            policyId,
            template.templateVersion,
            metadata.contentDigest,
            template.definition.ref,
            metadata.nodeId,
            calendarBinding,
            actionProfile,
            secondsToMillis(metadata.reminderAfterSeconds),
            secondsToMillis(metadata.dueAfterSeconds),
            secondsToMillis(metadata.escalationAfterSeconds),
        )
    }

    private fun secondsToMillis(seconds: Long): Long {
        require(seconds > 0L && seconds <= Long.MAX_VALUE / 1_000L) {
            "Workflow SLA metadata duration exceeds the runtime range."
        }
        return seconds * 1_000L
    }
}
