package com.fileweft.domain.document

import com.fileweft.core.id.Identifier
import java.util.Collections

class Document(
    val id: Identifier,
    val tenantId: Identifier,
    val assetId: Identifier,
    val documentNumber: String,
    title: String,
    lifecycleState: LifecycleState = LifecycleState.DRAFT,
    versions: List<DocumentVersion> = emptyList(),
    currentVersionId: Identifier? = null,
) {
    private val mutableVersions = ArrayList<DocumentVersion>()

    var title: String = title
        private set

    var lifecycleState: LifecycleState = lifecycleState
        private set

    var currentVersionId: Identifier? = currentVersionId
        private set

    val versions: List<DocumentVersion>
        get() = Collections.unmodifiableList(mutableVersions)

    init {
        require(documentNumber.isNotBlank()) { "Document number must not be blank." }
        require(title.isNotBlank()) { "Document title must not be blank." }
        versions.forEach(::validateVersion)
        require(versions.map { it.id }.distinct().size == versions.size) {
            "Document versions must have unique identifiers."
        }
        mutableVersions.addAll(versions)
        if (currentVersionId != null) {
            require(mutableVersions.any { it.id == currentVersionId }) {
                "Current version must belong to the document."
            }
        }
    }

    fun rename(newTitle: String) {
        require(canEdit()) { "Only draft or rejected documents can be renamed." }
        require(newTitle.isNotBlank()) { "Document title must not be blank." }
        title = newTitle
    }

    fun addVersion(version: DocumentVersion) {
        require(canEdit()) { "Only draft or rejected documents can accept a new version." }
        validateVersion(version)
        require(mutableVersions.none { it.id == version.id }) { "Version id already exists." }
        require(mutableVersions.none { it.versionNumber == version.versionNumber }) {
            "Version number already exists."
        }
        mutableVersions.add(version)
        currentVersionId = version.id
    }

    fun transition(command: LifecycleCommand) {
        lifecycleState = when (command) {
            LifecycleCommand.SUBMIT -> {
                require(currentVersionId != null) { "A document version is required before submission." }
                requireState(LifecycleState.DRAFT)
                LifecycleState.PENDING_REVIEW
            }

            LifecycleCommand.APPROVE -> nextState(command, LifecycleState.PENDING_REVIEW, LifecycleState.PUBLISHING)
            LifecycleCommand.REJECT -> nextState(command, LifecycleState.PENDING_REVIEW, LifecycleState.REJECTED)
            LifecycleCommand.REVISE -> nextState(command, LifecycleState.REJECTED, LifecycleState.DRAFT)
            LifecycleCommand.PUBLISH_SUCCEEDED -> nextState(command, LifecycleState.PUBLISHING, LifecycleState.PUBLISHED)
            LifecycleCommand.SYNC_FAILED -> nextState(command, LifecycleState.PUBLISHING, LifecycleState.SYNC_ERROR)
            LifecycleCommand.RETRY_SYNC -> nextState(command, LifecycleState.SYNC_ERROR, LifecycleState.PUBLISHING)
            LifecycleCommand.ARCHIVE -> nextState(command, LifecycleState.PUBLISHED, LifecycleState.HISTORY)
            LifecycleCommand.OFFLINE -> nextState(command, LifecycleState.PUBLISHED, LifecycleState.OFFLINE)
        }
    }

    private fun canEdit(): Boolean =
        lifecycleState == LifecycleState.DRAFT || lifecycleState == LifecycleState.REJECTED

    private fun validateVersion(version: DocumentVersion) {
        require(version.tenantId == tenantId) { "Version tenant must match document tenant." }
        require(version.documentId == id) { "Version document id must match document id." }
    }

    private fun requireState(expected: LifecycleState) {
        if (lifecycleState != expected) {
            throw InvalidLifecycleTransitionException(lifecycleState, LifecycleCommand.SUBMIT)
        }
    }

    private fun nextState(
        command: LifecycleCommand,
        expected: LifecycleState,
        next: LifecycleState,
    ): LifecycleState {
        if (lifecycleState != expected) {
            throw InvalidLifecycleTransitionException(lifecycleState, command)
        }
        return next
    }
}
