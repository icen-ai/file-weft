package com.fileweft.application.idempotency;

import com.fileweft.core.id.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaRequestIdempotencyInteropTest {
    @Test
    void exposesJavaFriendlyFactoriesResultsAndCommandCallbacks() {
        String rawKey = "java-client-key";
        RequestIdempotency request = RequestIdempotency.create(
            new Identifier("tenant-1"),
            new Identifier("operator-1"),
            rawKey,
            "document:submit",
            "DOCUMENT",
            new Identifier("document-1"),
            RequestFingerprint.sha256("document:submit", null)
        );

        assertEquals("tenant-1", request.getTenantId().getValue());
        assertFalse(request.getKeyDigest().contains(rawKey));
        assertNull(request.getSubresourceId());

        IdempotencyResult receipt = new IdempotencyResult(
            "DOCUMENT",
            new Identifier("document-1"),
            "WORKFLOW",
            new Identifier("workflow-1")
        );
        IdempotentCommand<String> command = () -> new IdempotentCommandResult<>("created", receipt);
        IdempotencyReplayMapper<String> replay = stored -> "replay:" + stored.getResourceId().getValue();

        assertEquals("created", command.execute().getValue());
        assertEquals("replay:document-1", replay.map(receipt));
        assertEquals("workflow-1", receipt.getRelatedResourceId().getValue());

        assertEquals(2, IdempotencyResult.class.getConstructors().length);
        assertThrows(
            NoSuchMethodException.class,
            () -> IdempotencyResult.class.getConstructor(String.class, Identifier.class, String.class)
        );
    }
}
