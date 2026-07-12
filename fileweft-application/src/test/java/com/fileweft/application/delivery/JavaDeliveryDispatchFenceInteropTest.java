package com.fileweft.application.delivery;

import com.fileweft.core.id.Identifier;
import com.fileweft.spi.delivery.DeliveryRequirement;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDeliveryDispatchFenceInteropTest {

    @Test
    void preservesTheOriginalTargetConstructorAndUsesFenceContractsFromJava() {
        Constructor<?> original = Arrays.stream(DocumentDeliveryTarget.class.getConstructors())
            .filter(constructor -> Arrays.equals(constructor.getParameterTypes(), new Class<?>[] {
                Identifier.class,
                Identifier.class,
                Identifier.class,
                String.class,
                String.class,
                String.class,
                String.class,
                DeliveryRequirement.class,
                String.class,
                DocumentDeliveryStatus.class,
                String.class,
                String.class,
                int.class,
                DocumentDeliveryRemovalStatus.class,
                String.class,
                int.class,
                int.class
            }))
            .findFirst()
            .orElse(null);

        assertNotNull(original);

        DocumentDeliveryTarget target = new DocumentDeliveryTarget(
            new Identifier("delivery-java"),
            new Identifier("tenant-java"),
            new Identifier("document-java"),
            "profile-java",
            "archive",
            "Archive",
            "archive-connector",
            DeliveryRequirement.REQUIRED,
            null,
            DocumentDeliveryStatus.PENDING,
            null,
            null,
            0,
            DocumentDeliveryRemovalStatus.NOT_REQUESTED,
            null,
            0,
            0
        );

        DeliveryDispatchFence fence = target.bindInitialDelivery(new Identifier("event-java"));

        assertEquals(DeliveryDispatchOperation.DELIVERY, fence.getOperation());
        assertEquals(1L, fence.getSequence());
        assertEquals("event-java", target.getCurrentDispatchFence().getEventId().getValue());
        assertTrue(target.matchesDispatch(
            new Identifier("event-java"),
            DeliveryDispatchOperation.DELIVERY,
            1L
        ));
    }
}
