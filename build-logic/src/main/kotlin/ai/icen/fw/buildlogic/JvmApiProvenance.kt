package ai.icen.fw.buildlogic

import java.io.File
import java.security.MessageDigest

/** Raw-byte provenance checks that must run before any API or export file is parsed. */
object JvmApiProvenance {
    private val digestPattern = Regex("^[0-9a-f]{64}$")

    @JvmStatic
    fun sha256(file: File): String {
        require(file.isFile && file.length() > 0L) {
            "JVM API provenance file is missing or empty: ${file.absolutePath}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    @JvmStatic
    fun requireDigest(file: File, expectedSha256: String, description: String): String {
        require(digestPattern.matches(expectedSha256)) {
            "$description has an invalid expected SHA-256."
        }
        val actual = sha256(file)
        require(actual == expectedSha256) {
            "$description bytes differ from provenance; expected=$expectedSha256, actual=$actual."
        }
        return actual
    }
}
