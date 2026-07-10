package com.fileweft.application.doctor

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorStatus
import com.fileweft.spi.storage.StorageObjectLocation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StorageDoctorCheckerTest {
    @Test
    fun `reports error when the active version file object record is missing`() {
        val result = checker(InMemoryFileObjectRepository(), true).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
    }

    @Test
    fun `reports error when storage object is absent`() {
        val result = checker(InMemoryFileObjectRepository(fileObject()), false).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
    }

    @Test
    fun `converts storage inspection failures into diagnostic errors`() {
        val storage = object : StorageAdapterStub() {
            override fun exists(location: StorageObjectLocation): Boolean = throw IllegalStateException("offline")
        }
        val result = StorageDoctorChecker(
            InMemoryDocumentRepository(documentWithActiveVersion()),
            InMemoryFileObjectRepository(fileObject()),
            storage,
        ).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals(IllegalStateException::class.java.name, result.evidence["exceptionType"])
    }

    @Test
    fun `reports healthy when the active file object is available`() {
        val result = checker(InMemoryFileObjectRepository(fileObject()), true).check(context())

        assertEquals(DoctorStatus.HEALTHY, result.status)
    }

    private fun checker(fileObjects: InMemoryFileObjectRepository, exists: Boolean): StorageDoctorChecker =
        StorageDoctorChecker(
            InMemoryDocumentRepository(documentWithActiveVersion()),
            fileObjects,
            object : StorageAdapterStub() {
                override fun exists(location: StorageObjectLocation): Boolean = exists
            },
        )

    private fun context() = DoctorCheckContext(Identifier("tenant-1"), Identifier("document-1"))
}
