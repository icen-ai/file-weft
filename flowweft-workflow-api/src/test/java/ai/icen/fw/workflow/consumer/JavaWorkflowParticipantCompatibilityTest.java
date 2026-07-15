package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.api.WorkflowDefinitionRef;
import ai.icen.fw.workflow.api.WorkflowDelegationMode;
import ai.icen.fw.workflow.api.WorkflowDelegationPolicy;
import ai.icen.fw.workflow.api.WorkflowInstanceRef;
import ai.icen.fw.workflow.api.WorkflowParticipantResolution;
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionReason;
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionRequest;
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionStage;
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionStatus;
import ai.icen.fw.workflow.api.WorkflowParticipantResolver;
import ai.icen.fw.workflow.api.WorkflowParticipantSelector;
import ai.icen.fw.workflow.api.WorkflowParticipantSelectorKind;
import ai.icen.fw.workflow.api.WorkflowParticipantTier;
import ai.icen.fw.workflow.api.WorkflowPrincipalRef;
import ai.icen.fw.workflow.api.WorkflowSubjectRef;
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot;
import ai.icen.fw.workflow.api.WorkflowWorkItemRef;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowParticipantCompatibilityTest {
    private static final String DIGEST_A =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String DIGEST_B =
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    void selectorsRequestTiersAndResolutionAreCallableFromExternalJava8() {
        WorkflowParticipantSelector group = WorkflowParticipantSelector.group("group-java");
        WorkflowParticipantSelector managers = WorkflowParticipantSelector.currentActorManagerChain(1, 2);
        List<WorkflowParticipantSelector> selectorSource =
            new ArrayList<WorkflowParticipantSelector>(Arrays.asList(group, managers));
        WorkflowParticipantResolutionRequest request = request(selectorSource);
        selectorSource.clear();

        assertEquals(2, request.getSelectors().size());
        assertEquals("tenant-java", request.getTenantId());
        assertEquals("corp.hr", request.getOrganizationAuthority());
        assertEquals("org-r1", request.getOrganizationSnapshotRevision());
        assertSame(WorkflowParticipantResolutionStage.ACTIVATION, request.getStage());
        assertEquals(64, request.getRequestDigest().length());
        assertSame(WorkflowParticipantSelectorKind.GROUP, group.getKind());
        assertEquals(Integer.valueOf(1), managers.getMinimumManagerLevel());
        assertEquals(Integer.valueOf(2), managers.getMaximumManagerLevel());

        WorkflowPrincipalRef groupPrincipal = principal("group-user");
        WorkflowPrincipalRef managerPrincipal = groupPrincipal;
        WorkflowParticipantTier groupTier = WorkflowParticipantTier.direct(
            group,
            0,
            Collections.singletonList(groupPrincipal),
            DIGEST_A
        );
        WorkflowParticipantTier managerTier = WorkflowParticipantTier.manager(
            managers,
            1,
            1,
            Collections.singletonList(managerPrincipal),
            DIGEST_B
        );
        WorkflowParticipantResolution resolution = WorkflowParticipantResolution.resolved(
            request,
            Arrays.asList(groupTier, managerTier),
            10_010L,
            10_090L
        );

        assertSame(WorkflowParticipantResolutionStatus.RESOLVED, resolution.getStatus());
        assertEquals(Arrays.asList(groupPrincipal, managerPrincipal), resolution.getPrincipals());
        assertEquals(Arrays.asList(groupTier, managerTier), resolution.getTiers());
        assertEquals("corp.hr", resolution.getAuthority());
        assertEquals("org-r1", resolution.getAuthorityRevision());
        assertEquals(64, resolution.getResolutionDigest().length());
        assertThrows(UnsupportedOperationException.class, () -> request.getSelectors().clear());
        assertThrows(UnsupportedOperationException.class, () -> resolution.getTiers().clear());
        assertThrows(UnsupportedOperationException.class, () -> resolution.getPrincipals().clear());

        WorkflowParticipantResolver resolver = actual -> CompletableFuture.completedFuture(resolution);
        assertSame(resolution, resolver.resolve(request).toCompletableFuture().join());
    }

    @Test
    void explicitFailureOutcomesAndExtensionCodesRemainValueFree() {
        WorkflowParticipantResolutionRequest request = request(Arrays.asList(
            WorkflowParticipantSelector.group("group-java"),
            WorkflowParticipantSelector.currentActorManagerChain(1, 2)
        ));
        WorkflowParticipantResolution empty = WorkflowParticipantResolution.empty(
            request,
            WorkflowParticipantResolutionReason.NO_MATCH,
            10_010L,
            10_020L
        );
        WorkflowParticipantResolution error = WorkflowParticipantResolution.error(
            request,
            WorkflowParticipantResolutionReason.of("corp.directory-timeout"),
            true,
            10_010L,
            10_020L
        );

        assertSame(WorkflowParticipantResolutionStatus.EMPTY, empty.getStatus());
        assertTrue(empty.getPrincipals().isEmpty());
        assertEquals("corp.directory-timeout", error.getReason().getCode());
        assertTrue(error.getRetryable());
        assertEquals(
            WorkflowParticipantSelectorKind.of("corp.project-owner"),
            WorkflowParticipantSelectorKind.of("corp.project-owner")
        );
        assertEquals("WorkflowParticipantResolution(<redacted>)", error.toString());
        assertFalse(error.toString().contains("corp.directory-timeout"));
    }

    @Test
    void publicContractsAreFinalPrivateConstructedAndNotAuthorizationPermits() {
        assertImmutableValue(WorkflowParticipantSelectorKind.class);
        assertImmutableValue(WorkflowParticipantSelector.class);
        assertImmutableValue(WorkflowDelegationMode.class);
        assertImmutableValue(WorkflowDelegationPolicy.class);
        assertImmutableValue(WorkflowParticipantResolutionStatus.class);
        assertImmutableValue(WorkflowParticipantResolutionReason.class);
        assertImmutableValue(WorkflowParticipantResolutionRequest.class);
        assertImmutableValue(WorkflowParticipantTier.class);
        assertImmutableValue(WorkflowParticipantResolution.class);

        for (Method method : WorkflowParticipantResolution.class.getMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("authorize"));
            assertFalse(name.contains("permit"));
            assertFalse(name.contains("allowed"));
        }
        for (Method method : WorkflowParticipantSelector.class.getMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("expression"));
            assertFalse(name.contains("script"));
        }
    }

    @Test
    void nullElementsDuplicatesAndLimitsFailClosedForJavaCallers() {
        WorkflowParticipantSelector group = WorkflowParticipantSelector.group("group-java");
        List<WorkflowParticipantSelector> withNull = new ArrayList<WorkflowParticipantSelector>();
        withNull.add(group);
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> request(withNull));
        assertThrows(IllegalArgumentException.class, () -> request(Arrays.asList(group, group)));

        List<WorkflowPrincipalRef> duplicatePrincipals = Arrays.asList(principal("same"), principal("same"));
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowParticipantTier.direct(group, 0, duplicatePrincipals, DIGEST_A)
        );
    }

    private static WorkflowParticipantResolutionRequest request(List<WorkflowParticipantSelector> selectors) {
        return WorkflowParticipantResolutionRequest.of(
            "request-java",
            "tenant-java",
            WorkflowDefinitionRef.of("definition", "V1", DIGEST_A),
            WorkflowInstanceRef.of("instance", 1L),
            WorkflowWorkItemRef.of("work-item", 2L),
            WorkflowParticipantResolutionStage.ACTIVATION,
            WorkflowSubjectSnapshot.of(WorkflowSubjectRef.of("DOCUMENT", "subject"), "R1", DIGEST_B),
            principal("initiator"),
            principal("actor"),
            "corp.hr",
            "org-r1",
            selectors,
            WorkflowDelegationPolicy.includeActiveDelegates(2),
            16,
            10_000L,
            10_100L
        );
    }

    private static WorkflowPrincipalRef principal(String id) {
        return WorkflowPrincipalRef.of("USER", id);
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
