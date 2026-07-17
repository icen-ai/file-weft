package ai.icen.fw.application.workflow

/** A configured review-route provider returned data that FlowWeft cannot persist safely. */
class DocumentReviewRouteConfigurationException @JvmOverloads constructor(
    message: String = DEFAULT_MESSAGE,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    companion object {
        const val DEFAULT_MESSAGE: String = "Document review route configuration is invalid."
    }
}
