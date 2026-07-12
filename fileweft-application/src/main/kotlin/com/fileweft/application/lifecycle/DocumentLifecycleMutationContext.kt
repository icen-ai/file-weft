package com.fileweft.application.lifecycle

import com.fileweft.application.catalog.DocumentLifecycleMutationGuard
import com.fileweft.application.catalog.DocumentLifecycleMutationPermit
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.spi.identity.UserIdentity

/**
 * Invocation-local identity and optional catalog evidence for one lifecycle
 * mutation. Creation happens only after base authorization. Catalog calls are
 * exposed as explicit prepare/revalidate phases so no caller has to place a
 * remote host SPI inside the final database transaction.
 */
internal class DocumentLifecycleMutationContext private constructor(
    val tenantId: Identifier,
    val operator: UserIdentity,
    val documentId: Identifier,
    val action: String,
    private val guard: DocumentLifecycleMutationGuard?,
    private val permit: DocumentLifecycleMutationPermit?,
) {
    private val validationProof = Any()

    init {
        require((guard == null) == (permit == null)) {
            "Lifecycle mutation guard and permit must be supplied together."
        }
    }

    @JvmSynthetic
    fun revalidate(): ValidatedDocumentLifecycleMutation {
        guard?.revalidateLifecycle(tenantId, operator, documentId, checkNotNull(permit))
        return ValidatedDocumentLifecycleMutation(this, validationProof)
    }

    private fun verifyLocked(document: Document) {
        guard?.verifyLifecycleLocked(tenantId, document, checkNotNull(permit))
    }

    private fun requireValidated(
        validated: ValidatedDocumentLifecycleMutation,
        expectedAction: String,
    ): DocumentLifecycleMutationContext {
        require(validated.matches(this, validationProof)) {
            "Lifecycle mutation validation token does not belong to this operation."
        }
        require(action == expectedAction) {
            "Lifecycle mutation context belongs to a different action."
        }
        return this
    }

    companion object {
        @JvmSynthetic
        fun prepare(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            action: String,
            guard: DocumentLifecycleMutationGuard?,
        ): DocumentLifecycleMutationContext = DocumentLifecycleMutationContext(
            tenantId = tenantId,
            operator = operator,
            documentId = documentId,
            action = action,
            guard = guard,
            permit = guard?.prepareLifecycle(tenantId, operator, documentId, action),
        )
    }

    internal class ValidatedDocumentLifecycleMutation internal constructor(
        private val source: DocumentLifecycleMutationContext,
        private val proof: Any,
    ) {
        internal fun matches(context: DocumentLifecycleMutationContext, expectedProof: Any): Boolean =
            source === context && proof === expectedProof

        @JvmSynthetic
        internal fun contextFor(expectedAction: String): DocumentLifecycleMutationContext =
            source.requireValidated(this, expectedAction)

        @JvmSynthetic
        internal fun verifyLocked(document: Document, expectedAction: String) {
            source.requireValidated(this, expectedAction).verifyLocked(document)
        }
    }
}

internal typealias ValidatedDocumentLifecycleMutation =
    DocumentLifecycleMutationContext.ValidatedDocumentLifecycleMutation

/**
 * A second, thread-bound proof that an ambient mutation is running inside the
 * caller's final local transaction. It is entered only around the mutation
 * callback itself, never around catalog or policy resolution.
 */
internal object DocumentLifecycleMutationTransaction {
    private val depth = ThreadLocal<Int>()

    @JvmSynthetic
    fun <T> execute(action: () -> T): T {
        depth.set((depth.get() ?: 0) + 1)
        return try {
            action()
        } finally {
            val remaining = (depth.get() ?: 1) - 1
            if (remaining == 0) depth.remove() else depth.set(remaining)
        }
    }

    @JvmSynthetic
    fun requireActive() {
        check((depth.get() ?: 0) > 0) {
            "Lifecycle ambient mutation requires the caller's active final transaction."
        }
    }
}
