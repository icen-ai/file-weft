---
route: "extensions/connectors"
group: "extensions"
order: 2
locale: "en"
nav: "Connector engineering"
title: "Connect unreliable systems"
lead: "A connector translates a stable FileWeft delivery contract into one vendor integration. Timeouts, retries, idempotency, removal and health are part of its design, not afterthoughts."
format: "html"
---

<h2 data-step="01">Required behavior</h2>
<ul><li>Bound every network call with a timeout.</li><li>Use the stable target or document identity as the downstream idempotency key.</li><li>Classify retryable and permanent failures without leaking credentials.</li><li>Return an external ID for later explicit removal.</li><li>Implement a read-only health check suitable for Doctor.</li></ul>

<h2 data-step="02">Multiple targets</h2>
<p><code>DocumentDeliveryProfileProvider</code> returns tenant-specific profiles containing required or optional target definitions. <code>DeliveryConnectorResolver</code> maps each stable <code>connectorId</code> to a connector instance without exposing Spring or vendor SDKs to SPI.</p>

<h2 data-step="03">Test the contract</h2>
<p>Integration tests should prove idempotent repeated delivery, safe repeated removal, timeout, retry classification, redacted failure text, health behavior and recovery after the external system becomes available.</p>
