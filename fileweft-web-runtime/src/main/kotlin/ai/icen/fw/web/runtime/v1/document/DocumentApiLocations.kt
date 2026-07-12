package ai.icen.fw.web.runtime.v1.document

import java.net.URI

/** Stable relative locations shared by both Spring MVC generations. */
class DocumentApiLocations private constructor() {
    companion object {
        @JvmStatic
        fun detailIfRoutable(documentId: String): URI? = documentId
            .takeIf { value -> value.length <= MAX_PATH_SEGMENT_LENGTH && ROUTABLE_SEGMENT.matches(value) }
            ?.let { value -> URI.create("$DOCUMENTS_PATH/$value") }

        private const val DOCUMENTS_PATH: String = "/fileweft/v1/documents"
        private const val MAX_PATH_SEGMENT_LENGTH: Int = 128
        private val ROUTABLE_SEGMENT: Regex = Regex("[A-Za-z0-9_~-](?:[A-Za-z0-9._~-]{0,127})")
    }
}
