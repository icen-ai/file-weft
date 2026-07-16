package ai.icen.fw.workflow.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.icen.fw.workflow.api.WorkflowHumanTaskEvidenceBinding;
import ai.icen.fw.workflow.api.WorkflowParticipantSelector;
import ai.icen.fw.workflow.api.WorkflowPredicateInputMapping;
import ai.icen.fw.workflow.api.WorkflowPredicateInputSourceKind;
import ai.icen.fw.workflow.api.WorkflowPredicateRef;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JavaWorkflowReferenceTemplateCompatibilityTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void javaCanBindAndPlanTheFixedLeaveTemplate() {
        WorkflowTemplateProfileRef subjectProfile = WorkflowTemplateProfileRef.of(
            WorkflowTemplateProfileKind.SUBJECT,
            "subject-registry",
            "1.0.0",
            A
        );
        WorkflowTemplateSubjectBinding subject = WorkflowTemplateSubjectBinding.of(
            "leave-request",
            subjectProfile
        );
        Map<String, WorkflowTemplateProfileRef> profiles = new HashMap<String, WorkflowTemplateProfileRef>();
        profiles.put(
            WorkflowReferenceTemplateSlots.ORGANIZATION_PROFILE,
            profile(WorkflowTemplateProfileKind.ORGANIZATION, "organization")
        );
        profiles.put(
            WorkflowReferenceTemplateSlots.FORM_PROFILE,
            profile(WorkflowTemplateProfileKind.FORM, "form")
        );
        profiles.put(
            WorkflowReferenceTemplateSlots.PREDICATE_PROFILE,
            profile(WorkflowTemplateProfileKind.PREDICATE, "predicate")
        );
        profiles.put(
            WorkflowReferenceTemplateSlots.CALENDAR_PROFILE,
            profile(WorkflowTemplateProfileKind.BUSINESS_CALENDAR, "calendar")
        );

        WorkflowHumanTaskEvidenceBinding evidence = WorkflowHumanTaskEvidenceBinding.of(
            "leave-form",
            "1.0.0",
            A,
            "leave-rule",
            "1.0.0",
            B
        );
        Map<String, WorkflowHumanTaskEvidenceBinding> evidenceBindings =
            new HashMap<String, WorkflowHumanTaskEvidenceBinding>();
        evidenceBindings.put(WorkflowReferenceTemplateSlots.LEAVE_EVIDENCE, evidence);

        WorkflowPredicateRef predicate = WorkflowPredicateRef.of(
            "reference-rules",
            "second-manager-required",
            "1.0.0",
            A,
            Arrays.asList(
                WorkflowPredicateInputMapping.of(
                    "days",
                    WorkflowPredicateInputSourceKind.WORKFLOW_VARIABLE,
                    "leave-days"
                )
            )
        );
        Map<String, WorkflowPredicateRef> predicates = new HashMap<String, WorkflowPredicateRef>();
        predicates.put(WorkflowReferenceTemplateSlots.LEAVE_SECOND_MANAGER_PREDICATE, predicate);

        WorkflowReferenceTemplateBindings bindings = WorkflowReferenceTemplateBindings.of(
            subject,
            Collections.<String, WorkflowParticipantSelector>emptyMap(),
            evidenceBindings,
            predicates,
            profiles
        );
        WorkflowReferenceTemplate template = WorkflowReferenceTemplates.leave(
            "tenant-java",
            "leave-java",
            bindings
        );

        assertEquals(WorkflowReferenceTemplates.TEMPLATE_VERSION, template.getTemplateVersion());
        assertFalse(template.getDefinition().getNodes().isEmpty());
        assertEquals(true, WorkflowReferenceTemplateLinter.lint(template).getValid());
        WorkflowTemplateSimulationPlan plan = WorkflowTemplateSimulationPlanner.plan(
            template,
            template.simulationCase("leave-two-level-approved"),
            WorkflowTemplateSimulationCapabilities.none()
        );
        assertEquals(WorkflowTemplateSimulationStatus.UNSUPPORTED, plan.getStatus());
        assertNotNull(plan.getFailureCode());
    }

    private static WorkflowTemplateProfileRef profile(WorkflowTemplateProfileKind kind, String id) {
        return WorkflowTemplateProfileRef.of(kind, id, "1.0.0", B);
    }
}
