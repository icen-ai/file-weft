package ai.icen.fw.application.outbox;

import ai.icen.fw.core.event.OutboxEvent;
import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.spi.event.OutboxHandlingResult;
import ai.icen.fw.spi.event.OutboxHandlingStatus;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class JavaOutboxEventMutationInteropTest {
    @Test
    void exposesJavaFriendlyStateRepositoryAndLeasedHandlerContracts() throws Exception {
        Identifier tenantId = new Identifier("tenant-1");
        Identifier eventId = new Identifier("event-1");
        OutboxEvent event = new OutboxEvent(
            eventId,
            tenantId,
            "document.delivery.target.requested",
            Collections.emptyMap(),
            1L
        );
        OutboxEventLease lease = new OutboxEventLease(event, 0, "worker-a", "token-a");
        OutboxEventState state = new OutboxEventState(
            eventId,
            tenantId,
            OutboxEventStatus.RUNNING,
            "worker-a",
            "token-a"
        );
        OutboxEventMutationRepository repository = (tenant, id) -> state;
        LeasedOutboxEventHandler handler = new LeasedOutboxEventHandler() {
            @Override
            public boolean supports(OutboxEvent candidate) {
                return true;
            }

            @Override
            public OutboxHandlingResult handle(OutboxEvent candidate) {
                return new OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "lease required");
            }

            @Override
            public OutboxHandlingResult handle(OutboxEventLease candidate) {
                candidate.getLeaseToken();
                return new OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED);
            }

            @Override
            public void onExhausted(OutboxEvent candidate, String message) {
                // Java implementors retain the legacy SPI callback explicitly.
            }
        };

        assertSame(state, repository.findForMutation(tenantId, eventId));
        assertSame(state, state.requireCurrentLease(lease));
        assertEquals(null, state.getEventType());
        assertEquals(
            "document.delivery.target.requested",
            new OutboxEventState(
                eventId,
                tenantId,
                OutboxEventStatus.FAILED,
                null,
                null,
                "document.delivery.target.requested"
            ).getEventType()
        );
        assertEquals(OutboxHandlingStatus.SUCCEEDED, handler.handle(lease).getStatus());
        assertNotNull(
            OutboxEventMutationRepository.class.getMethod("findForMutation", Identifier.class, Identifier.class)
        );
        assertNotNull(LeasedOutboxEventHandler.class.getMethod("handle", OutboxEventLease.class));
        assertNotNull(
            OutboxEventState.class.getConstructor(
                Identifier.class,
                Identifier.class,
                OutboxEventStatus.class,
                String.class,
                String.class
            )
        );
    }
}
