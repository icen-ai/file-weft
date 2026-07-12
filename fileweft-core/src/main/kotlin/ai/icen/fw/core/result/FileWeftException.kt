package ai.icen.fw.core.result

class FileWeftException(
    val error: ErrorDetail,
) : RuntimeException("${error.code}: ${error.message}")
