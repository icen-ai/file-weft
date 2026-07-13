package ai.icen.fw.sample.host

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.testkit.storage.StorageAdapterContractTest

class SampleStorageAdapterContractTest : StorageAdapterContractTest() {

    override val storageAdapter = SampleStorageAdapter()

    override fun uploadRequest(): StorageUploadRequest {
        return StorageUploadRequest(
            tenantId = Identifier("sample-tenant"),
            objectName = "contract-object-${System.nanoTime()}",
            contentLength = 1,
        )
    }
}
