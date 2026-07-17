package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class WorkflowHumanTaskCollaborationStateTest {
    @Test
    fun `delegate preserves owner transfer replaces owner and cycles fail closed`() {
        val owner = principal("owner")
        val delegate = principal("delegate")
        val transferred = principal("transferred")
        var state = WorkflowHumanTaskCollaborationState.unclaimed().transition(
            "record-claim",
            WorkflowHumanCollaborationAction.CLAIM,
            owner,
            null,
            DIGEST,
            "nonce-claim",
            1L,
        )
        state = state.transition(
            "record-delegate",
            WorkflowHumanCollaborationAction.DELEGATE,
            owner,
            delegate,
            DIGEST,
            "nonce-delegate",
            2L,
        )
        assertEquals(owner, state.claimOwner)
        assertEquals(delegate, state.activeDelegate)

        assertFailsWith<IllegalArgumentException> {
            state.transition(
                "record-cycle",
                WorkflowHumanCollaborationAction.DELEGATE,
                delegate,
                owner,
                DIGEST,
                "nonce-cycle",
                3L,
            )
        }
        state = state.transition(
            "record-transfer",
            WorkflowHumanCollaborationAction.TRANSFER,
            delegate,
            transferred,
            DIGEST,
            "nonce-transfer",
            3L,
        )
        assertEquals(transferred, state.claimOwner)
        assertNull(state.activeDelegate)
        assertEquals(listOf(owner, delegate, transferred), state.assignmentPath)
        assertFailsWith<IllegalArgumentException> {
            state.transition(
                "record-replayed-nonce",
                WorkflowHumanCollaborationAction.UNCLAIM,
                transferred,
                null,
                DIGEST,
                "nonce-transfer",
                4L,
            )
        }
    }

    @Test
    fun `delegation depth is bounded`() {
        val principals = (0..WorkflowHumanTaskCollaborationState.MAX_ASSIGNMENT_DEPTH)
            .map { principal("actor-$it") }
        var state = WorkflowHumanTaskCollaborationState.unclaimed().transition(
            "depth-record-0",
            WorkflowHumanCollaborationAction.CLAIM,
            principals.first(),
            null,
            DIGEST,
            "depth-nonce-0",
            1L,
        )
        for (index in 1 until WorkflowHumanTaskCollaborationState.MAX_ASSIGNMENT_DEPTH) {
            state = state.transition(
                "depth-record-$index",
                WorkflowHumanCollaborationAction.DELEGATE,
                principals[index - 1],
                principals[index],
                DIGEST,
                "depth-nonce-$index",
                index.toLong() + 1L,
            )
        }
        assertEquals(WorkflowHumanTaskCollaborationState.MAX_ASSIGNMENT_DEPTH, state.assignmentPath.size)
        assertFailsWith<IllegalArgumentException> {
            state.transition(
                "depth-record-overflow",
                WorkflowHumanCollaborationAction.DELEGATE,
                principals[WorkflowHumanTaskCollaborationState.MAX_ASSIGNMENT_DEPTH - 1],
                principals.last(),
                DIGEST,
                "depth-nonce-overflow",
                100L,
            )
        }
    }

    @Test
    fun `nested add-sign returns one exact frame and rejects cycles`() {
        val owner = principal("owner")
        val firstSigner = principal("first-signer")
        val secondSigner = principal("second-signer")
        var state = WorkflowHumanTaskCollaborationState.unclaimed().transition(
            "add-sign-claim",
            WorkflowHumanCollaborationAction.CLAIM,
            owner,
            null,
            DIGEST,
            "add-sign-claim-nonce",
            1L,
        )
        state = state.transition(
            "add-sign-first",
            WorkflowHumanCollaborationAction.ADD_SIGN,
            owner,
            firstSigner,
            DIGEST,
            "add-sign-first-nonce",
            2L,
        )
        state = state.transition(
            "add-sign-second",
            WorkflowHumanCollaborationAction.ADD_SIGN,
            firstSigner,
            secondSigner,
            DIGEST,
            "add-sign-second-nonce",
            3L,
        )
        assertEquals(2, state.addSignFrames.size)
        assertEquals(secondSigner, state.effectiveActor)
        assertFailsWith<IllegalArgumentException> {
            state.transition(
                "add-sign-cycle",
                WorkflowHumanCollaborationAction.ADD_SIGN,
                secondSigner,
                owner,
                DIGEST,
                "add-sign-cycle-nonce",
                4L,
            )
        }

        state = state.transition(
            "return-second",
            WorkflowHumanCollaborationAction.RETURN,
            secondSigner,
            firstSigner,
            DIGEST,
            "return-second-nonce",
            5L,
        )
        assertEquals(firstSigner, state.effectiveActor)
        assertEquals(1, state.addSignFrames.size)
        state = state.transition(
            "return-first",
            WorkflowHumanCollaborationAction.RETURN,
            firstSigner,
            owner,
            DIGEST,
            "return-first-nonce",
            6L,
        )
        assertEquals(owner, state.claimOwner)
        assertNull(state.activeDelegate)
        assertEquals(emptyList(), state.addSignFrames)
        assertEquals(listOf(owner), state.assignmentPath)
    }

    @Test
    fun `add-sign stack enforces the shared assignment depth limit`() {
        val principals = (0..WorkflowHumanTaskCollaborationState.MAX_ASSIGNMENT_DEPTH)
            .map { principal("signer-$it") }
        var state = WorkflowHumanTaskCollaborationState.unclaimed().transition(
            "add-depth-claim",
            WorkflowHumanCollaborationAction.CLAIM,
            principals.first(),
            null,
            DIGEST,
            "add-depth-claim-nonce",
            1L,
        )
        for (index in 1 until WorkflowHumanTaskCollaborationState.MAX_ASSIGNMENT_DEPTH) {
            state = state.transition(
                "add-depth-$index",
                WorkflowHumanCollaborationAction.ADD_SIGN,
                principals[index - 1],
                principals[index],
                DIGEST,
                "add-depth-nonce-$index",
                index.toLong() + 1L,
            )
        }
        assertEquals(WorkflowHumanTaskCollaborationState.MAX_ASSIGNMENT_DEPTH, state.assignmentPath.size)
        assertEquals(WorkflowHumanTaskCollaborationState.MAX_ASSIGNMENT_DEPTH - 1, state.addSignFrames.size)
        assertFailsWith<IllegalArgumentException> {
            state.transition(
                "add-depth-overflow",
                WorkflowHumanCollaborationAction.ADD_SIGN,
                principals[WorkflowHumanTaskCollaborationState.MAX_ASSIGNMENT_DEPTH - 1],
                principals.last(),
                DIGEST,
                "add-depth-overflow-nonce",
                100L,
            )
        }
    }

    private fun principal(id: String): WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", id)

    private companion object {
        const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
