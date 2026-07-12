package ai.icen.fw.application.document

data class CreateDocumentDraftCommand(
    val documentNumber: String,
    val title: String,
    val fileName: String,
    val contentLength: Long,
    val contentType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(documentNumber.isNotBlank()) { "Document number must not be blank." }
        require(title.isNotBlank()) { "Document title must not be blank." }
        require(fileName.isNotBlank()) { "File name must not be blank." }
        require(contentLength >= 0) { "File content length must not be negative." }
    }
}

data class AddDocumentVersionCommand(
    val versionNumber: String,
    val fileName: String,
    val contentLength: Long,
    val contentType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(versionNumber.isNotBlank()) { "Document version number must not be blank." }
        require(fileName.isNotBlank()) { "File name must not be blank." }
        require(contentLength >= 0) { "File content length must not be negative." }
    }
}
