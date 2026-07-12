package ai.icen.fw.application.upload

import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileObject

data class UploadFileResult(
    val fileObject: FileObject,
    val fileAsset: FileAsset,
)
