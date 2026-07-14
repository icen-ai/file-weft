package ai.icen.fw.application.upload

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResumableUploadServiceFacadeContractTest {
    @Test
    fun `preserves the released Java constructor overloads`() {
        val requiredParameters = listOf(
            TenantProvider::class.java,
            UserRealmProvider::class.java,
            AuthorizationProvider::class.java,
            StorageAdapter::class.java,
            ResumableUploadSessionRepository::class.java,
            FileObjectRepository::class.java,
            FileAssetRepository::class.java,
            OutboxEventRepository::class.java,
            IdentifierGenerator::class.java,
            ApplicationTransaction::class.java,
            Clock::class.java,
        )
        val expected = setOf(
            requiredParameters,
            requiredParameters + Duration::class.java,
            requiredParameters + listOf(Duration::class.java, FileWeftMetrics::class.java),
        )
        val actual = ResumableUploadService::class.java.declaredConstructors
            .asSequence()
            .filter { constructor -> Modifier.isPublic(constructor.modifiers) && !constructor.isSynthetic }
            .map { constructor -> constructor.parameterTypes.toList() }
            .toSet()

        assertEquals(expected, actual)
    }

    @Test
    fun `preserves the released Java method surface`() {
        val identifier = Identifier::class.java
        val intType = Int::class.javaPrimitiveType!!
        val longType = Long::class.javaPrimitiveType!!
        val expected = setOf(
            MethodSignature("start", listOf(StartResumableUploadCommand::class.java), ResumableUploadSession::class.java),
            MethodSignature(
                "startAndInspect",
                listOf(StartResumableUploadCommand::class.java),
                ResumableUploadSessionView::class.java,
            ),
            MethodSignature(
                "startAndInspectWithCallerKey",
                listOf(StartResumableUploadCommand::class.java),
                ResumableUploadSessionView::class.java,
            ),
            MethodSignature("inspect", listOf(identifier), ResumableUploadSessionView::class.java),
            MethodSignature(
                "uploadPart",
                listOf(identifier, intType, longType, InputStream::class.java),
                ResumableUploadPart::class.java,
            ),
            MethodSignature("complete", listOf(identifier), UploadFileResult::class.java),
            MethodSignature(
                "completeAndInspect",
                listOf(identifier),
                ResumableUploadCompletionResult::class.java,
            ),
            MethodSignature("abort", listOf(identifier), ResumableUploadSession::class.java),
            MethodSignature("abortAndInspect", listOf(identifier), ResumableUploadSessionView::class.java),
            MethodSignature("cleanupExpired", emptyList(), ExpiredResumableUploadCleanupResult::class.java),
            MethodSignature("cleanupExpired", listOf(intType), ExpiredResumableUploadCleanupResult::class.java),
            MethodSignature("inspectStalledCompletionsAsSystem", emptyList(), List::class.java),
            MethodSignature("inspectStalledCompletionsAsSystem", listOf(intType), List::class.java),
            MethodSignature("inspectStalledCompletions", emptyList(), List::class.java),
            MethodSignature("inspectStalledCompletions", listOf(intType), List::class.java),
        )
        val actual = ResumableUploadService::class.java.declaredMethods
            .asSequence()
            .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }
            .map { method -> MethodSignature(method.name, method.parameterTypes.toList(), method.returnType) }
            .toSet()

        assertEquals(expected, actual)
    }

    @Test
    fun `wires substantive collaborators to one shared context without a behavior engine`() {
        val service = ResumableUploadService(
            tenantProvider = unusedProxy(),
            userRealmProvider = unusedProxy(),
            authorizationProvider = unusedProxy(),
            storageAdapter = unusedProxy(),
            sessions = unusedProxy(),
            fileObjects = unusedProxy(),
            fileAssets = unusedProxy(),
            outbox = unusedProxy(),
            identifiers = unusedProxy(),
            transaction = unusedProxy(),
            clock = Clock.systemUTC(),
        )
        val context = fieldValue(service, ResumableUploadContext::class.java)
        val collaborators = listOf(
            fieldValue(service, ResumableUploadStarter::class.java),
            fieldValue(service, ResumableUploadPartHandler::class.java),
            fieldValue(service, ResumableUploadCompletionHandler::class.java),
            fieldValue(service, ResumableUploadAbortHandler::class.java),
            fieldValue(service, ResumableUploadReconciler::class.java),
            fieldValue(service, ResumableUploadCleanupService::class.java),
        )

        collaborators.forEach { collaborator ->
            assertSame(context, fieldValue(collaborator, ResumableUploadContext::class.java))
        }

        val workflowMethods = setOf(
            "start",
            "startAndInspect",
            "startAndInspectWithCallerKey",
            "inspect",
            "uploadPart",
            "complete",
            "completeAndInspect",
            "abort",
            "abortAndInspect",
            "cleanupExpired",
            "inspectStalledCompletionsAsSystem",
            "inspectStalledCompletions",
        )
        assertTrue(
            ResumableUploadContext::class.java.declaredMethods.none { method -> method.name in workflowMethods },
            "The shared context must not grow back into a resumable-upload behavior engine.",
        )
        assertFalse(
            (sequenceOf<Any>(service) + collaborators.asSequence())
                .flatMap { owner -> owner.javaClass.declaredFields.asSequence() }
                .any { field -> field.type.simpleName == "ResumableUploadEngine" },
            "The facade graph must not retain the former ResumableUploadEngine.",
        )

        val declaredWorkflowOwners = mapOf(
            ResumableUploadStarter::class.java to
                setOf("start", "startAndInspect", "startAndInspectWithCallerKey"),
            ResumableUploadPartHandler::class.java to setOf("uploadPart"),
            ResumableUploadCompletionHandler::class.java to setOf("complete", "completeAndInspect"),
            ResumableUploadAbortHandler::class.java to setOf("abort", "abortAndInspect"),
            ResumableUploadReconciler::class.java to setOf("inspect"),
            ResumableUploadCleanupService::class.java to
                setOf("cleanupExpired", "inspectStalledCompletionsAsSystem", "inspectStalledCompletions"),
        )
        declaredWorkflowOwners.forEach { (owner, expectedMethods) ->
            val actualMethods = owner.declaredMethods.map { method -> method.name }.toSet()
            assertTrue(expectedMethods.all(actualMethods::contains), "${owner.simpleName} does not own its workflow.")
        }
    }

    private fun <T : Any> fieldValue(owner: Any, type: Class<T>): T {
        val field = owner.javaClass.declaredFields.single { candidate -> candidate.type == type }
        field.isAccessible = true
        return type.cast(field.get(owner))
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> unusedProxy(): T {
        val type = T::class.java
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            throw AssertionError("${type.simpleName}.${method.name} must not be called while assembling the facade.")
        } as T
    }

    private data class MethodSignature(
        val name: String,
        val parameterTypes: List<Class<*>>,
        val returnType: Class<*>,
    )
}
