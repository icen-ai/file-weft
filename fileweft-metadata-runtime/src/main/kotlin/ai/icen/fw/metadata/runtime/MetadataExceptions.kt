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

class MetadataSchemaConfigurationException :
    IllegalStateException(MESSAGE) {
    companion object {
        const val MESSAGE = "Metadata schema configuration is invalid."
    }
}
