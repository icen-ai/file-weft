package com.fileweft.spi.storage

import java.io.InputStream
import java.net.URI
import java.time.Duration

interface StorageAdapter {
    fun upload(request: StorageUploadRequest, content: InputStream): StoredObject

    fun download(location: StorageObjectLocation): StorageDownload

    fun delete(location: StorageObjectLocation)

    fun exists(location: StorageObjectLocation): Boolean

    fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI

    fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload

    fun uploadPart(
        upload: MultipartUpload,
        partNumber: Int,
        content: InputStream,
        contentLength: Long,
    ): MultipartPart

    fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject

    fun abortMultipartUpload(upload: MultipartUpload)
}
