package ai.icen.fw.dev.platform

import org.springframework.boot.context.properties.ConfigurationProperties

/** Configuration owned by the isolated downstream-platform development process. */
@ConfigurationProperties(prefix = "fileweft.dev.platform")
class DevPlatformProperties {
    /** Required shared credential used by the FileWeft development API and worker. */
    var sharedSecret: String = ""

    /** Explicit host allowlist for the one-time source URLs supplied by the connector. */
    var allowedDownloadHosts: MutableList<String> = mutableListOf("rustfs")

    /** Stops a simulator request from consuming unbounded network bandwidth. */
    var maxDownloadBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES

    companion object {
        const val DEFAULT_MAX_DOWNLOAD_BYTES: Long = 512L * 1024L * 1024L
    }
}
