package ai.icen.fw.metadata.runtime

internal object MetadataRuntimeLimits {
    const val MAX_FIELDS = 128
    const val MAX_FIELD_NAME_CODE_POINTS = 128
    const val MAX_VALUE_UTF16_LENGTH = 16_384
    const val MAX_TOTAL_INPUT_UTF16_LENGTH = 65_536
    const val MAX_ISSUES = 128
    const val MAX_STRING_LIST_ITEMS = 64
    const val MAX_STRING_LIST_ITEM_UTF16_LENGTH = 2_048
    const val MAX_NUMBER_SOURCE_UTF16_LENGTH = 512
    const val MAX_NUMBER_PRECISION = 256
    const val MAX_NUMBER_SCALE_MAGNITUDE = 256
}
