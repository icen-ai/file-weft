package ai.icen.fw.adapter.storage

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Filesystem-backed [StorageAdapter] for development and single-node deployments.
 *
 * Returned locations are opaque, tenant-scoped paths. The caller supplied object
 * name is deliberately not used in the filesystem path, so it cannot introduce
 * traversal or platform-specific filename issues. Object hashes are canonical
 * SHA-256 values in the form `sha256:<lowercase-hex>`. A caller-provided hash
 * is never trusted as a stored result; the value returned by this adapter is
 * always calculated from the streamed content.
 *
 * A local filesystem cannot enforce a time-limited URL by itself. [accessUrl]
 * therefore returns a `file:` URI after validating the requested lifetime; hosts
 * that expose these files must enforce their own access control.
 */
class LocalStorageAdapter(
    rootDirectory: Path,
    private val storageType: String = STORAGE_TYPE,
) : StorageAdapter {
    private val root: Path = prepareRoot(rootDirectory)

    init {
        require(storageType.isNotBlank()) { "Storage type must not be blank." }
    }

    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
        val location = newObjectLocation(request.tenantId)
        val temporaryObject = createStagingFile("object")
        try {
            val copied = writeContent(content, temporaryObject)
            require(copied.contentLength == request.contentLength) {
                "Uploaded content length does not match the declared content length."
            }
            publishObject(
                location = location,
                temporaryObject = temporaryObject,
                metadata = ObjectMetadata(
                    contentType = request.contentType,
                    contentHash = copied.contentHash,
                    userMetadata = request.metadata,
                ),
            )
            return StoredObject(location, copied.contentLength, request.contentType, copied.contentHash)
        } finally {
            Files.deleteIfExists(temporaryObject)
        }
    }

    override fun download(location: StorageObjectLocation): StorageDownload {
        val objectPath = existingObjectPath(location)
        val metadata = readMetadata(location)
        return StorageDownload(
            content = Files.newInputStream(objectPath),
            contentLength = Files.size(objectPath),
            contentType = metadata.contentType,
        )
    }

    override fun delete(location: StorageObjectLocation) {
        val objectPath = resolveObjectPath(location)
        Files.deleteIfExists(objectPath)
        Files.deleteIfExists(metadataPath(location))
    }

    override fun exists(location: StorageObjectLocation): Boolean {
        val objectPath = resolveObjectPath(location)
        return Files.isRegularFile(objectPath, NOFOLLOW_LINKS) && !Files.isSymbolicLink(objectPath)
    }

    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI {
        require(!expiresIn.isNegative && !expiresIn.isZero) { "Access URL expiration must be positive." }
        return existingObjectPath(location).toUri()
    }

    override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload {
        val uploadId = UUID.randomUUID().toString()
        val location = newObjectLocation(request.tenantId)
        val uploadDirectory = uploadDirectory(uploadId)
        ensureDirectory(uploadDirectory.parent)
        Files.createDirectory(uploadDirectory)
        try {
            writeSession(
                uploadDirectory,
                MultipartSession(
                    location = location,
                    contentLength = request.contentLength,
                    metadata = ObjectMetadata(
                        contentType = request.contentType,
                        contentHash = null,
                        userMetadata = request.metadata,
                    ),
                ),
            )
        } catch (failure: Throwable) {
            Files.deleteIfExists(uploadDirectory.resolve(SESSION_FILE_NAME))
            Files.deleteIfExists(uploadDirectory)
            throw failure
        }
        return MultipartUpload(Identifier(uploadId), location)
    }

    override fun uploadPart(
        upload: MultipartUpload,
        partNumber: Int,
        content: InputStream,
        contentLength: Long,
    ): MultipartPart {
        require(partNumber > 0) { "Part number must be positive." }
        require(contentLength >= 0) { "Part content length must not be negative." }
        val session = readSession(upload)
        val target = partPath(session.directory, partNumber)
        val temporaryPart = createStagingFile("part")
        try {
            val copied = writeContent(content, temporaryPart)
            require(copied.contentLength == contentLength) {
                "Part content length does not match the declared content length."
            }
            move(temporaryPart, target, replaceExisting = true)
            return MultipartPart(partNumber, copied.contentHash.removePrefix(HASH_PREFIX))
        } finally {
            Files.deleteIfExists(temporaryPart)
        }
    }

    override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject {
        require(parts.isNotEmpty()) { "At least one multipart upload part is required." }
        val session = readSession(upload)
        val requestedParts = parts.associateBy { it.partNumber }
        require(requestedParts.size == parts.size) { "Multipart upload parts must not contain duplicates." }

        val storedParts = listPartNumbers(session.directory)
        require(storedParts.keys == requestedParts.keys) {
            "Completed parts must exactly match the uploaded parts."
        }
        requestedParts.forEach { (partNumber, part) ->
            val actualHash = digest(partPath(session.directory, partNumber)).removePrefix(HASH_PREFIX)
            require(actualHash == part.eTag) { "Multipart upload part eTag does not match its content." }
        }

        val temporaryObject = createStagingFile("multipart-object")
        try {
            val copied = concatenateParts(storedParts, temporaryObject)
            require(copied.contentLength == session.contentLength) {
                "Completed multipart content length does not match the declared content length."
            }
            publishObject(
                location = session.location,
                temporaryObject = temporaryObject,
                metadata = session.metadata.copy(contentHash = copied.contentHash),
            )
            cleanupUploadDirectory(session.directory)
            return StoredObject(session.location, copied.contentLength, session.metadata.contentType, copied.contentHash)
        } finally {
            Files.deleteIfExists(temporaryObject)
        }
    }

    override fun abortMultipartUpload(upload: MultipartUpload) {
        val directory = uploadDirectory(upload.uploadId.value)
        if (!Files.exists(directory, NOFOLLOW_LINKS)) {
            return
        }
        readSession(upload)
        cleanupUploadDirectory(directory)
    }

    private fun newObjectLocation(tenantId: Identifier): StorageObjectLocation =
        StorageObjectLocation(
            storageType,
            "objects/${digest(tenantId.value.toByteArray(StandardCharsets.UTF_8)).removePrefix(HASH_PREFIX)}/${UUID.randomUUID().toString().replace("-", "")}",
        )

    private fun existingObjectPath(location: StorageObjectLocation): Path {
        val objectPath = resolveObjectPath(location)
        require(Files.isRegularFile(objectPath, NOFOLLOW_LINKS) && !Files.isSymbolicLink(objectPath)) {
            "Stored object does not exist."
        }
        return objectPath
    }

    private fun resolveObjectPath(location: StorageObjectLocation): Path {
        require(location.storageType == storageType) { "Storage location does not belong to this adapter." }
        require(OBJECT_PATH_PATTERN.matches(location.path)) { "Storage location path is invalid." }
        val target = root.resolve(location.path).normalize()
        require(target.startsWith(root)) { "Storage location escapes the configured root directory." }
        assertSafeDirectory(target.parent)
        require(!Files.isSymbolicLink(target)) { "Symbolic links are not valid stored objects." }
        return target
    }

    private fun metadataPath(location: StorageObjectLocation): Path {
        val objectPath = resolveObjectPath(location)
        val target = root.resolve(METADATA_DIRECTORY).resolve(root.relativize(objectPath).toString() + METADATA_SUFFIX).normalize()
        require(target.startsWith(root)) { "Metadata path escapes the configured root directory." }
        assertSafeDirectory(target.parent)
        require(!Files.isSymbolicLink(target)) { "Symbolic links are not valid object metadata." }
        return target
    }

    private fun createStagingFile(prefix: String): Path {
        val stagingDirectory = root.resolve(STAGING_DIRECTORY)
        ensureDirectory(stagingDirectory)
        return Files.createTempFile(stagingDirectory, "$prefix-", TEMPORARY_SUFFIX)
    }

    private fun publishObject(location: StorageObjectLocation, temporaryObject: Path, metadata: ObjectMetadata) {
        val objectPath = resolveObjectPath(location)
        val objectMetadataPath = metadataPath(location)
        require(!Files.exists(objectPath, NOFOLLOW_LINKS)) { "Storage object already exists." }
        ensureDirectory(objectPath.parent)
        ensureDirectory(objectMetadataPath.parent)
        val temporaryMetadata = createStagingFile("metadata")
        try {
            writeMetadata(temporaryMetadata, metadata)
            move(temporaryObject, objectPath, replaceExisting = false)
            try {
                move(temporaryMetadata, objectMetadataPath, replaceExisting = false)
            } catch (failure: Throwable) {
                try {
                    Files.deleteIfExists(objectPath)
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        } finally {
            Files.deleteIfExists(temporaryMetadata)
        }
    }

    private fun writeContent(content: InputStream, target: Path): CopiedContent =
        Files.newOutputStream(target).use { output -> copyAndDigest(content, output) }

    private fun concatenateParts(parts: Map<Int, Path>, target: Path): CopiedContent =
        Files.newOutputStream(target).use { output ->
            val digest = newSha256()
            var contentLength = 0L
            parts.toSortedMap().forEach { (_, part) ->
                Files.newInputStream(part).use { input ->
                    copyAndDigest(input, output, digest) { copiedLength ->
                        contentLength = Math.addExact(contentLength, copiedLength)
                    }
                }
            }
            CopiedContent(contentLength, HASH_PREFIX + digest.digest().toHex())
        }

    private fun copyAndDigest(content: InputStream, output: OutputStream): CopiedContent {
        val digest = newSha256()
        var contentLength = 0L
        copyAndDigest(content, output, digest) { copiedLength ->
            contentLength = Math.addExact(contentLength, copiedLength)
        }
        return CopiedContent(contentLength, HASH_PREFIX + digest.digest().toHex())
    }

    private fun copyAndDigest(
        content: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        onBytesCopied: (Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = content.read(buffer)
            if (read < 0) {
                return
            }
            if (read == 0) {
                continue
            }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            onBytesCopied(read.toLong())
        }
    }

    private fun digest(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = newSha256()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            if (read > 0) {
                digest.update(buffer, 0, read)
            }
        }
        HASH_PREFIX + digest.digest().toHex()
    }

    private fun digest(content: ByteArray): String = HASH_PREFIX + newSha256().digest(content).toHex()

    private fun writeMetadata(path: Path, metadata: ObjectMetadata) {
        val lines = mutableListOf(
            "version=1",
            "contentType=${encode(metadata.contentType)}",
            "contentHash=${encode(metadata.contentHash)}",
        )
        metadata.userMetadata.toSortedMap().forEach { (key, value) ->
            lines += "metadata.${encode(key)}=${encode(value)}"
        }
        Files.write(path, lines, StandardCharsets.UTF_8)
    }

    private fun readMetadata(location: StorageObjectLocation): ObjectMetadata {
        val path = metadataPath(location)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            return ObjectMetadata()
        }
        val values = readKeyValues(path)
        require(values["version"] == "1") { "Stored object metadata version is not supported." }
        val userMetadata = values
            .filterKeys { it.startsWith("metadata.") }
            .mapKeys { (key, _) -> decode(key.removePrefix("metadata.")) ?: error("Metadata key is missing.") }
            .mapValues { (_, value) -> decode(value) ?: error("Metadata value is missing.") }
        return ObjectMetadata(
            contentType = decode(values["contentType"]),
            contentHash = decode(values["contentHash"]),
            userMetadata = userMetadata,
        )
    }

    private fun writeSession(directory: Path, session: MultipartSession) {
        val lines = mutableListOf(
            "version=1",
            "location=${session.location.path}",
            "contentLength=${session.contentLength}",
            "contentType=${encode(session.metadata.contentType)}",
        )
        session.metadata.userMetadata.toSortedMap().forEach { (key, value) ->
            lines += "metadata.${encode(key)}=${encode(value)}"
        }
        Files.write(directory.resolve(SESSION_FILE_NAME), lines, StandardCharsets.UTF_8)
    }

    private fun readSession(upload: MultipartUpload): MultipartSessionHandle {
        require(upload.location.storageType == storageType) { "Multipart upload does not belong to this adapter." }
        val directory = uploadDirectory(upload.uploadId.value)
        require(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory)) {
            "Multipart upload does not exist."
        }
        val sessionPath = directory.resolve(SESSION_FILE_NAME)
        require(Files.isRegularFile(sessionPath, NOFOLLOW_LINKS) && !Files.isSymbolicLink(sessionPath)) {
            "Multipart upload session is invalid."
        }
        val values = readKeyValues(sessionPath)
        require(values["version"] == "1") { "Multipart upload session version is not supported." }
        val location = StorageObjectLocation(storageType, values["location"] ?: error("Multipart upload location is missing."))
        require(location == upload.location) { "Multipart upload location does not match its session." }
        val contentLength = values["contentLength"]?.toLongOrNull()
            ?: throw IllegalArgumentException("Multipart upload content length is invalid.")
        require(contentLength >= 0) { "Multipart upload content length is invalid." }
        val userMetadata = values
            .filterKeys { it.startsWith("metadata.") }
            .mapKeys { (key, _) -> decode(key.removePrefix("metadata.")) ?: error("Metadata key is missing.") }
            .mapValues { (_, value) -> decode(value) ?: error("Metadata value is missing.") }
        return MultipartSessionHandle(
            directory = directory,
            location = location,
            contentLength = contentLength,
            metadata = ObjectMetadata(
                contentType = decode(values["contentType"]),
                userMetadata = userMetadata,
            ),
        )
    }

    private fun uploadDirectory(uploadId: String): Path {
        val parsed = try {
            UUID.fromString(uploadId)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Multipart upload identifier is invalid.", failure)
        }
        require(parsed.toString() == uploadId) { "Multipart upload identifier is invalid." }
        val directory = root.resolve(UPLOAD_DIRECTORY).resolve(uploadId).normalize()
        require(directory.startsWith(root)) { "Multipart upload directory escapes the configured root directory." }
        assertSafeDirectory(directory.parent)
        require(!Files.isSymbolicLink(directory)) { "Symbolic links are not valid multipart upload directories." }
        return directory
    }

    private fun partPath(directory: Path, partNumber: Int): Path =
        directory.resolve("%010d.part".format(java.util.Locale.ROOT, partNumber))

    private fun listPartNumbers(directory: Path): Map<Int, Path> {
        val parts = linkedMapOf<Int, Path>()
        Files.list(directory).use { entries ->
            entries.forEach { entry ->
                if (Files.isRegularFile(entry, NOFOLLOW_LINKS) && PART_PATH_PATTERN.matches(entry.fileName.toString())) {
                    parts[entry.fileName.toString().removeSuffix(".part").toInt()] = entry
                }
            }
        }
        return parts
    }

    private fun cleanupUploadDirectory(directory: Path) {
        Files.list(directory).use { entries ->
            entries.forEach { entry ->
                val name = entry.fileName.toString()
                require(name == SESSION_FILE_NAME || PART_PATH_PATTERN.matches(name)) {
                    "Multipart upload directory contains an unexpected entry."
                }
                require(!Files.isSymbolicLink(entry)) { "Multipart upload directory contains a symbolic link." }
                Files.deleteIfExists(entry)
            }
        }
        Files.deleteIfExists(directory)
    }

    private fun readKeyValues(path: Path): Map<String, String> =
        Files.readAllLines(path, StandardCharsets.UTF_8).associate { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "Stored metadata is invalid." }
            line.substring(0, separator) to line.substring(separator + 1)
        }

    private fun encode(value: String?): String = value?.let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
    } ?: NULL_VALUE

    private fun decode(value: String?): String? {
        require(value != null) { "Stored metadata value is missing." }
        if (value == NULL_VALUE) {
            return null
        }
        return String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }

    private fun ensureDirectory(directory: Path) {
        val normalized = directory.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "Directory escapes the configured root directory." }
        val relativePath = root.relativize(normalized)
        var current = root
        for (segment in relativePath) {
            current = current.resolve(segment.toString())
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                require(Files.isDirectory(current, NOFOLLOW_LINKS) && !Files.isSymbolicLink(current)) {
                    "Managed storage directory is invalid."
                }
            } else {
                Files.createDirectory(current)
            }
        }
    }

    private fun assertSafeDirectory(directory: Path) {
        val normalized = directory.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "Directory escapes the configured root directory." }
        var current = root
        for (segment in root.relativize(normalized)) {
            current = current.resolve(segment.toString())
            if (!Files.exists(current, NOFOLLOW_LINKS)) {
                return
            }
            require(Files.isDirectory(current, NOFOLLOW_LINKS) && !Files.isSymbolicLink(current)) {
                "Managed storage directory is invalid."
            }
        }
    }

    private fun move(source: Path, target: Path, replaceExisting: Boolean) {
        try {
            if (replaceExisting) {
                Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } else {
                Files.move(source, target, ATOMIC_MOVE)
            }
        } catch (_: AtomicMoveNotSupportedException) {
            if (replaceExisting) {
                Files.move(source, target, REPLACE_EXISTING)
            } else {
                Files.move(source, target)
            }
        }
    }

    private data class CopiedContent(
        val contentLength: Long,
        val contentHash: String,
    )

    private data class ObjectMetadata(
        val contentType: String? = null,
        val contentHash: String? = null,
        val userMetadata: Map<String, String> = emptyMap(),
    )

    private data class MultipartSession(
        val location: StorageObjectLocation,
        val contentLength: Long,
        val metadata: ObjectMetadata,
    )

    private data class MultipartSessionHandle(
        val directory: Path,
        val location: StorageObjectLocation,
        val contentLength: Long,
        val metadata: ObjectMetadata,
    )

    private companion object {
        const val STORAGE_TYPE = "local"
        const val BUFFER_SIZE = 16 * 1024
        const val HASH_PREFIX = "sha256:"
        const val STAGING_DIRECTORY = ".staging"
        const val METADATA_DIRECTORY = ".metadata"
        const val UPLOAD_DIRECTORY = ".uploads"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val METADATA_SUFFIX = ".meta"
        const val SESSION_FILE_NAME = "session.meta"
        const val NULL_VALUE = "-"
        val OBJECT_PATH_PATTERN = Regex("^objects/[0-9a-f]{64}/[0-9a-f]{32}$")
        val PART_PATH_PATTERN = Regex("^[0-9]{10}\\.part$")

        fun prepareRoot(rootDirectory: Path): Path {
            val normalized = rootDirectory.toAbsolutePath().normalize()
            Files.createDirectories(normalized)
            require(Files.isDirectory(normalized, NOFOLLOW_LINKS)) {
                "Local storage root must be a directory."
            }
            return normalized.toRealPath()
        }

        fun newSha256(): MessageDigest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (failure: NoSuchAlgorithmException) {
            throw IllegalStateException("The JVM does not provide SHA-256.", failure)
        }

        fun ByteArray.toHex(): String {
            val hex = CharArray(size * 2)
            forEachIndexed { index, value ->
                val unsigned = value.toInt() and 0xff
                hex[index * 2] = HEX_DIGITS[unsigned ushr 4]
                hex[index * 2 + 1] = HEX_DIGITS[unsigned and 0x0f]
            }
            return String(hex)
        }

        const val HEX_DIGITS = "0123456789abcdef"
    }
}
