package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataValidationResult

class MetadataSchemaNotFoundException :
    NoSuchElementException(MESSAGE) {
    companion object {
        const val MESSAGE = "Metadata schema is not available."
    }
}

class MetadataValidationException(
    val validationResult: MetadataValidationResult,
) : IllegalArgumentException(MESSAGE) {
    companion object {
        const val MESSAGE = "Metadata validation failed."
    }
}

/**
 * Fixed-message host configuration failure. The message stays constant
 * because it can cross API boundaries; the original failure is retained as
 * [cause] for operators whenever one exists.
 */
class MetadataSchemaConfigurationException : IllegalStateException {
    constructor() : super(MESSAGE)
    constructor(cause: Throwable) : super(MESSAGE, cause)

    companion object {
        const val MESSAGE = "Metadata schema configuration is invalid."
    }
}
