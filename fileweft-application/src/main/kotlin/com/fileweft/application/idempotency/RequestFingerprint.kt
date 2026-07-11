package com.fileweft.application.idempotency

import java.security.MessageDigest

/** Stable, length-prefixed hashing for an already validated typed command. */
object RequestFingerprint {
    @JvmStatic
    fun sha256(vararg components: String?): String = Sha256Digest.digest(
        namespace = "fileweft-request-fingerprint-v1",
        components = components,
    )
}

internal object Sha256Digest {
    fun digest(namespace: String, components: Array<out String?>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        requireWellFormedUtf16(namespace, "Digest namespace")
        updateBytes(digest, namespace.toByteArray(Charsets.UTF_8))
        updateInt(digest, components.size)
        components.forEach { component ->
            if (component == null) {
                digest.update(0)
            } else {
                requireWellFormedUtf16(component, "Digest component")
                digest.update(1)
                updateBytes(digest, component.toByteArray(Charsets.UTF_8))
            }
        }
        return "sha256:" + digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun updateBytes(digest: MessageDigest, value: ByteArray) {
        updateInt(digest, value.size)
        digest.update(value)
    }

    private fun updateInt(digest: MessageDigest, value: Int) {
        digest.update((value ushr 24).toByte())
        digest.update((value ushr 16).toByte())
        digest.update((value ushr 8).toByte())
        digest.update(value.toByte())
    }
}

internal fun requireWellFormedUtf16(value: String, label: String): String {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    throw IllegalArgumentException("$label must contain well-formed Unicode text.")
                }
                index += 2
            }
            Character.isLowSurrogate(character) -> {
                throw IllegalArgumentException("$label must contain well-formed Unicode text.")
            }
            else -> index += 1
        }
    }
    return value
}
