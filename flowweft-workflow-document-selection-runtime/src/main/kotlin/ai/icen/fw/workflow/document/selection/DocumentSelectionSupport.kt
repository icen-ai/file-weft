package ai.icen.fw.workflow.document.selection

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object DocumentSelectionSupport {
    const val MAX_METADATA_ENTRIES = 64
    const val MAX_METADATA_TOTAL_BYTES = 65_536
    private val CODE = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
    private val SHA_256 = Regex("[0-9a-f]{64}")

    fun code(value: String, label: String): String = value.also {
        require(CODE.matches(it)) { "$label is invalid." }
    }

    fun text(value: String, label: String, maximumBytes: Int = 512): String = value.also {
        require(it.isNotEmpty() && it.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes) {
            "$label is invalid."
        }
        require(it.none { character ->
            Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
        }) { "$label is invalid." }
        require(!it.first().isWhitespace() && !it.last().isWhitespace()) { "$label is invalid." }
    }

    fun digest(value: String, label: String): String = value.also {
        require(SHA_256.matches(it)) { "$label must be a canonical SHA-256 digest." }
    }

    fun sha256(domain: String, vararg values: String?): String {
        code(domain, "Digest domain")
        val hash = MessageDigest.getInstance("SHA-256")
        update(hash, domain)
        hash.update(ByteBuffer.allocate(4).putInt(values.size).array())
        values.forEach { value ->
            if (value == null) {
                hash.update(0.toByte())
            } else {
                hash.update(1.toByte())
                update(hash, value)
            }
        }
        return hash.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun update(hash: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        hash.update(bytes)
    }
}
