package ai.icen.fw.reliability.api

/**
 * Canonical restart boundary for provider evidence whose original request is intentionally not
 * retained in the public receipt. Every reconstruction reruns value invariants and compares an
 * independently persisted digest. Adapters must not use reflection or Java serialization.
 */
object ReliabilityDurableEvidenceFactory {
    @JvmStatic
    fun rehydrateVerification(
        requestDigest: String,
        manifestDigest: String,
        providerId: String,
        providerRevision: String,
        status: ReliabilityManifestVerificationStatus,
        sealVerified: Boolean,
        artifactDigestsVerified: Boolean,
        encryptionReferencesVerified: Boolean,
        consistentCutVerified: Boolean,
        recoveryObjectivesVerified: Boolean,
        evidenceDigest: String,
        verifiedAtEpochMilli: Long,
        expiresAtEpochMilli: Long,
        expectedReceiptDigest: String,
    ): ReliabilityManifestVerificationReceipt {
        val restored = ReliabilityManifestVerificationReceipt.rehydrate(
            requestDigest,
            manifestDigest,
            providerId,
            providerRevision,
            status,
            sealVerified,
            artifactDigestsVerified,
            encryptionReferencesVerified,
            consistentCutVerified,
            recoveryObjectivesVerified,
            evidenceDigest,
            verifiedAtEpochMilli,
            expiresAtEpochMilli,
        )
        requireExpectedDigest(expectedReceiptDigest, restored.receiptDigest, "verification receipt")
        return restored
    }

    @JvmStatic
    fun rehydrateBackupReceipt(
        requestDigest: String,
        manifest: ReliabilityBackupManifest,
        providerId: String,
        providerRevision: String,
        providerEvidenceDigest: String,
        completedAtEpochMilli: Long,
        expectedReceiptDigest: String,
    ): ReliabilityBackupCreationReceipt {
        val restored = ReliabilityBackupCreationReceipt.rehydrate(
            requestDigest,
            manifest,
            providerId,
            providerRevision,
            providerEvidenceDigest,
            completedAtEpochMilli,
        )
        requireExpectedDigest(expectedReceiptDigest, restored.receiptDigest, "backup receipt")
        return restored
    }

    @JvmStatic
    fun rehydrateRestoreReceipt(
        requestDigest: String,
        targetBindingDigest: String,
        manifestDigest: String,
        providerId: String,
        providerRevision: String,
        providerEvidenceDigest: String,
        completedAtEpochMilli: Long,
        assessment: ReliabilityRecoveryAssessment,
        expectedReceiptDigest: String,
    ): ReliabilityRestoreReceipt {
        val expectedManifest = ReliabilityContractSupport.sha256(
            manifestDigest,
            "Reliability persisted restore manifest digest is invalid.",
        )
        require(assessment.manifestDigest == expectedManifest) {
            "Reliability persisted restore assessment is not bound to its manifest."
        }
        val restored = ReliabilityRestoreReceipt.rehydrate(
            requestDigest,
            targetBindingDigest,
            providerId,
            providerRevision,
            providerEvidenceDigest,
            completedAtEpochMilli,
            assessment,
        )
        requireExpectedDigest(expectedReceiptDigest, restored.receiptDigest, "restore receipt")
        return restored
    }

    @JvmStatic
    fun rehydrateDrillReport(
        requestDigest: String,
        drillId: String,
        targetBindingDigest: String,
        manifestDigest: String,
        providerId: String,
        providerRevision: String,
        providerEvidenceDigest: String,
        completedAtEpochMilli: Long,
        assessment: ReliabilityRecoveryAssessment,
        expectedReportDigest: String,
    ): ReliabilityDrillReport {
        val expectedManifest = ReliabilityContractSupport.sha256(
            manifestDigest,
            "Reliability persisted drill manifest digest is invalid.",
        )
        require(assessment.manifestDigest == expectedManifest) {
            "Reliability persisted drill assessment is not bound to its manifest."
        }
        val restored = ReliabilityDrillReport.rehydrate(
            requestDigest,
            drillId,
            targetBindingDigest,
            providerId,
            providerRevision,
            providerEvidenceDigest,
            completedAtEpochMilli,
            assessment,
        )
        requireExpectedDigest(expectedReportDigest, restored.reportDigest, "drill report")
        return restored
    }

    @JvmStatic
    fun rehydrateReconciliationReceipt(
        requestDigest: String,
        originalReferenceDigest: String,
        status: ReliabilityReconciliationStatus,
        providerEvidenceDigest: String,
        reconciledAtEpochMilli: Long,
        expectedReceiptDigest: String,
    ): ReliabilityReconciliationReceipt {
        val restored = ReliabilityReconciliationReceipt.rehydrate(
            requestDigest,
            originalReferenceDigest,
            status,
            providerEvidenceDigest,
            reconciledAtEpochMilli,
        )
        requireExpectedDigest(expectedReceiptDigest, restored.receiptDigest, "reconciliation receipt")
        return restored
    }

    private fun requireExpectedDigest(expected: String, actual: String, subject: String) {
        val canonical = ReliabilityContractSupport.sha256(
            expected,
            "Reliability persisted $subject digest is invalid.",
        )
        require(canonical == actual) { "Reliability persisted $subject digest does not match its canonical fields." }
    }
}
