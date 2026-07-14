package ai.icen.fw.application.upload

import ai.icen.fw.application.transaction.ApplicationTransactionBoundary
import ai.icen.fw.core.id.Identifier
import java.io.FilterInputStream
import java.io.InputStream

internal class ResumableUploadPartHandler(
    private val context: ResumableUploadContext,
) {
    fun uploadPart(
        sessionId: Identifier,
        partNumber: Int,
        contentLength: Long,
        content: InputStream,
    ): ResumableUploadPart {
        ApplicationTransactionBoundary.requireInactive(context.transaction)
        require(partNumber in 1..ResumableUploadPart.MAX_PART_NUMBER) {
            "Multipart part number must be between 1 and ${ResumableUploadPart.MAX_PART_NUMBER}."
        }
        require(contentLength > 0) { "Multipart part length must be positive." }
        val requestIdentity = context.currentRequestIdentity()
        val session = context.requiredOwnedSession(requestIdentity.tenantId, requestIdentity.ownerId, sessionId)
        context.authorize(session, requestIdentity.user)
        context.requireActive(session, context.clock.millis())
        val measured = CountingInputStream(content)
        val acknowledged = try {
            context.storageAdapter.uploadPart(context.storageUpload(session), partNumber, measured, contentLength)
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: Throwable) {
            throw ResumableUploadUnavailableException(failure)
        }
        require(acknowledged.partNumber == partNumber) {
            "Storage adapter acknowledged a different multipart part number."
        }
        require(measured.read() == -1 && measured.contentLength == contentLength) {
            "Multipart part body length does not match its declared content length."
        }
        return context.transaction.execute {
            val current = context.requiredOwnedSessionInTransaction(
                session.tenantId,
                requestIdentity.ownerId,
                session.id,
            )
            context.requireActive(current, context.clock.millis())
            val existing = context.sessions.findParts(current.tenantId, current.id)
                .firstOrNull { it.partNumber == partNumber }
            ResumableUploadPart(
                id = existing?.id ?: context.identifiers.nextId(),
                tenantId = current.tenantId,
                sessionId = current.id,
                partNumber = partNumber,
                eTag = acknowledged.eTag,
                contentLength = contentLength,
                createdTime = existing?.createdTime ?: context.clock.millis(),
                updatedTime = context.clock.millis(),
            ).also(context.sessions::savePart)
        }
    }

    /** Counts bytes actually consumed by an adapter without buffering the request body. */
    private class CountingInputStream(content: InputStream) : FilterInputStream(content) {
        var contentLength: Long = 0
            private set

        override fun read(): Int = super.read().also { value ->
            if (value >= 0) contentLength = Math.addExact(contentLength, 1L)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { read ->
                if (read > 0) contentLength = Math.addExact(contentLength, read.toLong())
            }
    }
}
