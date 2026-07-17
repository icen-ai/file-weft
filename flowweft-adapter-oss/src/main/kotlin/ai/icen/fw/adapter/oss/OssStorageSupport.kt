package ai.icen.fw.adapter.oss

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

internal fun sha256(value: ByteArray): String = newSha256().digest(value).toHex()

internal fun safeFingerprint(value: String): String = sha256(value.toByteArray(Charsets.UTF_8)).take(16)

internal fun newSha256(): MessageDigest = try {
    MessageDigest.getInstance("SHA-256")
} catch (failure: NoSuchAlgorithmException) {
    throw IllegalStateException("SHA-256 is unavailable in this JVM.", failure)
}

internal fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
