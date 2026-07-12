package com.fileweft.application.outbox

import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.event.OutboxHandlingResult

/**
 * Optional stronger handler contract for code that must fence its local
 * projection against the exact persisted Outbox lease that invoked it.
 *
 * Implementations still provide the legacy [OutboxEventHandler.handle]
 * method for callers that only know the SPI contract. The worker invokes this
 * overload when it owns an [OutboxEventLease], including leases returned by a
 * legacy repository without an owner/token. The handler deliberately decides
 * how to fail closed when a token is unavailable; the worker never fabricates
 * lease ownership.
 */
interface LeasedOutboxEventHandler : OutboxEventHandler {
    fun handle(lease: OutboxEventLease): OutboxHandlingResult
}
