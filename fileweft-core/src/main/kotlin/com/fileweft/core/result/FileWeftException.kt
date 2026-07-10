package com.fileweft.core.result

class FileWeftException(
    val error: ErrorDetail,
) : RuntimeException("${error.code}: ${error.message}")
