package ai.icen.fw.spi.storage

/**
 * Definitive rejection of one multipart-completion request.
 *
 * An adapter may throw this exception only when it can guarantee that the request did not publish
 * the final object and is no longer executing remotely. Timeouts, broken connections, acknowledgement
 * loss, ambiguous service errors, and `NoSuchUpload` after a possible earlier completion must not be
 * classified this way. The distinction lets Application safely reopen a checkpoint without racing a
 * completion whose outcome is still unknown.
 */
class MultipartCompletionRejectedException @JvmOverloads constructor(
    message: String = DEFAULT_MESSAGE,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    companion object {
        const val DEFAULT_MESSAGE: String = "Multipart completion was definitively rejected."
    }
}
