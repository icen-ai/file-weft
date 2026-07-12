package ai.icen.fw.domain.document

/**
 * Base type for expected document command conflicts.
 *
 * A conflict means the command input is structurally valid, but it cannot be
 * applied to the document's current business state. Keeping this as an
 * [IllegalStateException] preserves compatibility for existing callers while
 * giving outer adapters a stable type that does not depend on message text.
 */
open class DocumentConflictException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
