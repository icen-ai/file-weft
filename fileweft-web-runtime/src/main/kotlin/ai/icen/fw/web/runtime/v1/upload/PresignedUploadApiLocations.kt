package ai.icen.fw.web.runtime.v1.upload

import java.net.URI

/** Stable FlowWeft v1 resource locations for direct uploads. */
class PresignedUploadApiLocations private constructor() {
    companion object {
        @JvmStatic
        fun inspect(uploadId: String): URI = inspect(URI.create(UPLOADS_PATH), uploadId)

        @JvmStatic
        fun inspect(uploadsLocation: URI, uploadId: String): URI {
            check(uploadId.length <= 128 && ROUTABLE_SEGMENT.matches(uploadId)) {
                "The application issued a presigned upload identifier that cannot be represented as a resource path."
            }
            check(!uploadsLocation.isOpaque && uploadsLocation.rawQuery == null && uploadsLocation.rawFragment == null) {
                "The presigned upload collection location cannot identify a resource path."
            }
            return URI.create("${uploadsLocation.toASCIIString().trimEnd('/')}/$uploadId")
        }

        private const val UPLOADS_PATH: String = "/flowweft/v1/presigned-uploads"
        private val ROUTABLE_SEGMENT: Regex = Regex("[A-Za-z0-9_~-](?:[A-Za-z0-9._~-]{0,127})")
    }
}
