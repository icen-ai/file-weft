package ai.icen.fw.web.runtime.v1.upload

import java.net.URI

/** Stable upload resource locations shared by both Spring MVC generations. */
class ResumableUploadApiLocations private constructor() {
    companion object {
        @JvmStatic
        fun inspect(uploadId: String): URI = inspect(URI.create(UPLOADS_PATH), uploadId)

        /** Appends one validated upload segment to the collection URI seen by the current host request. */
        @JvmStatic
        fun inspect(uploadsLocation: URI, uploadId: String): URI {
            // Defense in depth: formal creation enforces the same pattern in Application before
            // side effects, while this check also protects custom facade implementations.
            check(uploadId.length <= MAX_PATH_SEGMENT_LENGTH && ROUTABLE_SEGMENT.matches(uploadId)) {
                "The application issued an upload identifier that cannot be represented as a v1 resource path."
            }
            check(!uploadsLocation.isOpaque && uploadsLocation.rawQuery == null && uploadsLocation.rawFragment == null) {
                "The upload collection location cannot identify a v1 resource path."
            }
            return URI.create("${uploadsLocation.toASCIIString().trimEnd('/')}/$uploadId")
        }

        private const val UPLOADS_PATH: String = "/fileweft/v1/uploads"
        private const val MAX_PATH_SEGMENT_LENGTH: Int = 128
        private val ROUTABLE_SEGMENT: Regex = Regex("[A-Za-z0-9_~-](?:[A-Za-z0-9._~-]{0,127})")
    }
}
