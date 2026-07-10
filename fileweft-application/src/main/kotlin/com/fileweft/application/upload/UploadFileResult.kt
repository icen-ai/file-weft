package com.fileweft.application.upload

import com.fileweft.domain.file.FileAsset
import com.fileweft.domain.file.FileObject

data class UploadFileResult(
    val fileObject: FileObject,
    val fileAsset: FileAsset,
)
