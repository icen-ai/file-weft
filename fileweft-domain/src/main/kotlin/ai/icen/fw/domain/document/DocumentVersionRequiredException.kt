package ai.icen.fw.domain.document

/** Submission was requested before the document had a current file version. */
class DocumentVersionRequiredException :
    DocumentConflictException("A document version is required before submission.")
