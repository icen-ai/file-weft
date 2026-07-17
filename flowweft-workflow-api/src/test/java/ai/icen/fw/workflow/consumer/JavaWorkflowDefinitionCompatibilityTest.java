package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.api.WorkflowApprovalMode;
import ai.icen.fw.workflow.api.WorkflowApprovalPolicy;
import ai.icen.fw.workflow.api.WorkflowDefinition;
import ai.icen.fw.workflow.api.WorkflowDefinitionStatus;
import ai.icen.fw.workflow.api.WorkflowHumanTaskCapabilities;
import ai.icen.fw.workflow.api.WorkflowHumanTaskParticipantRule;
import ai.icen.fw.workflow.api.WorkflowHumanTaskPolicy;
import ai.icen.fw.workflow.api.WorkflowNodeDefinition;
import ai.icen.fw.workflow.api.WorkflowNodeKind;
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionStage;
import ai.icen.fw.workflow.api.WorkflowParticipantSelector;
import ai.icen.fw.workflow.api.WorkflowPredicateInputMapping;
import ai.icen.fw.workflow.api.WorkflowPredicateInputSourceKind;
import ai.icen.fw.workflow.api.WorkflowPredicateRef;
import ai.icen.fw.workflow.api.WorkflowSeparationOfDutiesPolicy;
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition;
import ai.icen.fw.workflow.api.WorkflowTransitionTrigger;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowDefinitionCompatibilityTest {
    private static final String DIGEST_A =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String DIGEST_B =
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    void definitionHumanPolicyAndGraphAreCallableFromExternalJava8() {
        WorkflowApprovalPolicy approval = WorkflowApprovalPolicy.quorum(2);
        WorkflowHumanTaskParticipantRule rule = WorkflowHumanTaskParticipantRule.of(
            WorkflowParticipantSelector.departmentLeaders("legal-department"),
            approval
        );
        WorkflowHumanTaskCapabilities capabilities = WorkflowHumanTaskCapabilities.of(true, true, false, true);
        WorkflowSeparationOfDutiesPolicy separation = WorkflowSeparationOfDutiesPolicy.of(true, true);
        List<WorkflowParticipantResolutionStage> stageSource = new ArrayList<WorkflowParticipantResolutionStage>(
            Arrays.asList(
                WorkflowParticipantResolutionStage.ACTIVATION,
                WorkflowParticipantResolutionStage.CLAIM,
                WorkflowParticipantResolutionStage.DECISION
            )
        );
        WorkflowHumanTaskPolicy policy = WorkflowHumanTaskPolicy.of(
            Collections.singletonList(rule),
            capabilities,
            separation,
            stageSource
        );
        stageSource.clear();

        List<WorkflowNodeDefinition> nodeSource = new ArrayList<WorkflowNodeDefinition>(Arrays.asList(
            WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", null),
            WorkflowNodeDefinition.humanTask("review", "审批", "人工复核", policy),
            WorkflowNodeDefinition.of("approved", WorkflowNodeKind.END, "通过", null),
            WorkflowNodeDefinition.of("rejected", WorkflowNodeKind.END, "拒绝", null)
        ));
        List<WorkflowTransitionDefinition> transitionSource = new ArrayList<WorkflowTransitionDefinition>(Arrays.asList(
            WorkflowTransitionDefinition.unconditional("start-review", "start", "review"),
            WorkflowTransitionDefinition.unconditional(
                "review-approved",
                "review",
                "approved",
                WorkflowTransitionTrigger.APPROVED
            ),
            WorkflowTransitionDefinition.unconditional(
                "review-rejected",
                "review",
                "rejected",
                WorkflowTransitionTrigger.REJECTED
            )
        ));
        WorkflowDefinition definition = WorkflowDefinition.of(
            "tenant-java",
            "definition-java",
            "legal-approval",
            "v1",
            1,
            WorkflowDefinitionStatus.DRAFT,
            "Java 审批流程",
            "第一行\n第二行",
            nodeSource,
            transitionSource
        );
        nodeSource.clear();
        transitionSource.clear();

        assertEquals("tenant-java", definition.getTenantId());
        assertEquals("definition-java", definition.getDefinitionId());
        assertEquals("legal-approval", definition.getKey());
        assertEquals("v1", definition.getVersion());
        assertEquals(1, definition.getSchemaVersion());
        assertSame(WorkflowDefinitionStatus.DRAFT, definition.getStatus());
        assertEquals(definition.getContentDigest(), definition.getRef().getDigest());
        assertEquals(4, definition.getNodes().size());
        assertEquals(3, definition.getTransitions().size());
        assertSame(WorkflowTransitionTrigger.APPROVED, definition.getTransitions().get(1).getTrigger());
        assertSame(WorkflowTransitionTrigger.REJECTED, definition.getTransitions().get(2).getTrigger());
        assertSame(WorkflowApprovalMode.QUORUM, approval.getMode());
        assertEquals(Integer.valueOf(2), approval.getRequiredApprovals());
        assertTrue(capabilities.getAddSignEnabled());
        assertFalse(capabilities.getTransferEnabled());
        assertTrue(separation.getInitiatorExcluded());
        assertThrows(UnsupportedOperationException.class, () -> definition.getNodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> policy.getResolutionStages().clear());
    }

    @Test
    void predicatesAndProviderBackedNodesUseExactDigestBindingsWithoutExpressions() {
        WorkflowPredicateInputMapping amount = WorkflowPredicateInputMapping.of(
            "amount",
            WorkflowPredicateInputSourceKind.WORKFLOW_VARIABLE,
            "/request/amount"
        );
        WorkflowPredicateRef predicate = WorkflowPredicateRef.of(
            "corp.rules",
            "amount-limit",
            "v3",
            DIGEST_A,
            Collections.singletonList(amount)
        );
        WorkflowTransitionDefinition conditional = WorkflowTransitionDefinition.conditional(
            "route-approved",
            "route",
            "approved",
            predicate
        );
        assertSame(predicate, conditional.getPredicate());
        assertSame(WorkflowTransitionTrigger.COMPLETED, conditional.getTrigger());
        assertEquals(DIGEST_A, predicate.getDigest());
        assertEquals(64, predicate.getBindingDigest().length());

        WorkflowTransitionDefinition completed = WorkflowTransitionDefinition.unconditional(
            "outcome",
            "review",
            "next"
        );
        WorkflowTransitionDefinition rejected = WorkflowTransitionDefinition.unconditional(
            "outcome",
            "review",
            "next",
            WorkflowTransitionTrigger.REJECTED
        );
        assertNotEquals(completed.getContentDigest(), rejected.getContentDigest());

        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowNodeDefinition.of("service", WorkflowNodeKind.SERVICE_TASK, "服务", null)
        );
        WorkflowNodeDefinition first = WorkflowNodeDefinition.serviceTask(
            "service",
            "服务",
            null,
            DIGEST_A,
            DIGEST_B
        );
        WorkflowNodeDefinition second = WorkflowNodeDefinition.serviceTask(
            "service",
            "服务",
            null,
            DIGEST_B,
            DIGEST_A
        );
        assertNotEquals(first.getContentDigest(), second.getContentDigest());
        assertEquals(DIGEST_A, first.getDescriptorDigest());
        assertEquals(DIGEST_B, first.getPayloadDigest());

        WorkflowNodeDefinition extension = WorkflowNodeDefinition.extension(
            "signature",
            WorkflowNodeKind.of("corp.signature"),
            "签章",
            null,
            DIGEST_A,
            DIGEST_B
        );
        assertEquals("corp.signature", extension.getKind().getCode());
        assertNull(extension.getHumanTaskPolicy());
    }

    @Test
    void valuesAreFinalPrivateConstructedImmutableAndNotExecutionOrAuthorizationPermits() {
        Class<?>[] values = new Class<?>[] {
            WorkflowDefinitionStatus.class,
            WorkflowNodeKind.class,
            WorkflowApprovalMode.class,
            WorkflowApprovalPolicy.class,
            WorkflowParticipantResolutionStage.class,
            WorkflowHumanTaskCapabilities.class,
            WorkflowSeparationOfDutiesPolicy.class,
            WorkflowHumanTaskParticipantRule.class,
            WorkflowHumanTaskPolicy.class,
            WorkflowPredicateInputSourceKind.class,
            WorkflowPredicateInputMapping.class,
            WorkflowPredicateRef.class,
            WorkflowTransitionTrigger.class,
            WorkflowNodeDefinition.class,
            WorkflowTransitionDefinition.class,
            WorkflowDefinition.class
        };
        for (Class<?> value : values) {
            assertImmutableValue(value);
            assertEquals(value.getSimpleName() + "(<redacted>)", sample(value).toString());
        }

        for (Class<?> type : Arrays.asList(
            WorkflowDefinition.class,
            WorkflowNodeDefinition.class,
            WorkflowPredicateRef.class,
            WorkflowTransitionDefinition.class
        )) {
            for (Method method : type.getMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("authorize"));
                assertFalse(name.contains("permit"));
                assertFalse(name.contains("executable"));
                assertFalse(name.contains("deploy"));
                assertFalse(name.contains("expression"));
                assertFalse(name.equals("script") || name.equals("getscript") || name.contains("scripttext"));
            }
        }
    }

    private static Object sample(Class<?> type) {
        if (type == WorkflowDefinitionStatus.class) return WorkflowDefinitionStatus.DRAFT;
        if (type == WorkflowNodeKind.class) return WorkflowNodeKind.START;
        if (type == WorkflowApprovalMode.class) return WorkflowApprovalMode.ONE;
        if (type == WorkflowApprovalPolicy.class) return WorkflowApprovalPolicy.one();
        if (type == WorkflowParticipantResolutionStage.class) return WorkflowParticipantResolutionStage.ACTIVATION;
        if (type == WorkflowHumanTaskCapabilities.class) return WorkflowHumanTaskCapabilities.of(false, false, false, false);
        if (type == WorkflowSeparationOfDutiesPolicy.class) return WorkflowSeparationOfDutiesPolicy.none();
        WorkflowHumanTaskParticipantRule rule = WorkflowHumanTaskParticipantRule.of(
            WorkflowParticipantSelector.group("group"),
            WorkflowApprovalPolicy.one()
        );
        if (type == WorkflowHumanTaskParticipantRule.class) return rule;
        WorkflowHumanTaskPolicy policy = WorkflowHumanTaskPolicy.of(
            Collections.singletonList(rule),
            WorkflowHumanTaskCapabilities.of(false, false, false, false),
            WorkflowSeparationOfDutiesPolicy.none(),
            Collections.singletonList(WorkflowParticipantResolutionStage.ACTIVATION)
        );
        if (type == WorkflowHumanTaskPolicy.class) return policy;
        if (type == WorkflowPredicateInputSourceKind.class) return WorkflowPredicateInputSourceKind.CONTEXT_VALUE;
        WorkflowPredicateInputMapping mapping = WorkflowPredicateInputMapping.of(
            "input",
            WorkflowPredicateInputSourceKind.CONTEXT_VALUE,
            "source"
        );
        if (type == WorkflowPredicateInputMapping.class) return mapping;
        WorkflowPredicateRef predicate = WorkflowPredicateRef.of(
            "provider",
            "predicate",
            "v1",
            DIGEST_A,
            Collections.singletonList(mapping)
        );
        if (type == WorkflowPredicateRef.class) return predicate;
        if (type == WorkflowTransitionTrigger.class) return WorkflowTransitionTrigger.COMPLETED;
        WorkflowNodeDefinition start = WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", null);
        if (type == WorkflowNodeDefinition.class) return start;
        WorkflowTransitionDefinition transition = WorkflowTransitionDefinition.unconditional("t", "start", "end");
        if (type == WorkflowTransitionDefinition.class) return transition;
        if (type == WorkflowDefinition.class) {
            return WorkflowDefinition.of(
                "tenant",
                "definition",
                "key",
                "v1",
                1,
                WorkflowDefinitionStatus.DRAFT,
                "流程",
                null,
                Arrays.asList(start, WorkflowNodeDefinition.of("end", WorkflowNodeKind.END, "结束", null)),
                Collections.singletonList(transition)
            );
        }
        throw new AssertionError("No sample for " + type.getName());
    }

    private static void assertImmutableValue(Class<?> type) {
        assertTrue(Modifier.isFinal(type.getModifiers()), type.getName() + " must be final");
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!constructor.isSynthetic()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()), constructor + " must be private");
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertTrue(Modifier.isPrivate(field.getModifiers()), field + " must be private");
                assertTrue(Modifier.isFinal(field.getModifiers()), field + " must be final");
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            assertFalse(method.getName().startsWith("set"));
            assertFalse(method.getName().startsWith("component"));
            assertFalse(method.getName().equals("copy"));
        }
    }
}
