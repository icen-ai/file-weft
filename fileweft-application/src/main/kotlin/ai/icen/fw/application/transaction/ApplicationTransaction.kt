package ai.icen.fw.application.transaction

interface ApplicationTransaction {
    fun <T> execute(action: () -> T): T
}

/**
 * Optional, additive capability for transaction implementations that can
 * report whether their own persistence boundary is already active.
 *
 * Storage-backed application commands use this signal to reject an ambient
 * transaction before creating identifiers or invoking external storage. A
 * custom [ApplicationTransaction] that does not implement this capability
 * remains binary compatible, but its host is responsible for ensuring those
 * commands are not called from an active transaction.
 */
interface ApplicationTransactionState {
    fun isTransactionActive(): Boolean
}

/**
 * Enforces the non-nesting contract for commands that combine transactional
 * persistence with non-transactional external side effects.
 */
object ApplicationTransactionBoundary {
    @JvmStatic
    fun requireInactive(transaction: ApplicationTransaction) {
        val state = transaction as? ApplicationTransactionState ?: return
        if (state.isTransactionActive()) {
            throw ApplicationTransactionNestingException()
        }
    }
}

/**
 * Raised before a storage-backed command performs any work when its
 * persistence transaction is already active on the current thread.
 */
class ApplicationTransactionNestingException : IllegalStateException(DEFAULT_MESSAGE) {
    companion object {
        const val DEFAULT_MESSAGE: String =
            "A storage-backed FlowWeft command cannot run inside an active application transaction."
    }
}

/**
 * Signals that a transaction manager could not determine whether a commit succeeded.
 *
 * Callers must reconcile durable state before compensating non-transactional side effects.
 * In particular, deleting an external object solely because this exception was raised can
 * destroy data that was already referenced by a successful but unacknowledged commit.
 */
class ApplicationTransactionOutcomeUnknownException(
    cause: Throwable,
) : IllegalStateException(DEFAULT_MESSAGE, cause) {
    companion object {
        const val DEFAULT_MESSAGE: String = "The transaction outcome is unknown and requires reconciliation."
    }
}
