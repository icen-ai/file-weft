---
route: "extensions/plugins"
group: "extensions"
order: 1
locale: "en"
nav: "Plugin development"
title: "Build a disciplined plugin"
lead: "Plugins aggregate existing SPI contributions. They do not create a new architectural layer or an in-process security sandbox."
format: "html"
---

<h2 data-step="01">Contribution model</h2>
<p>A <code>FileWeftPlugin</code> may contribute connectors, storage, Doctor checkers, task handlers, review routes, metrics or agents through existing contracts. Contribution getters are called once during registry construction and captured as immutable snapshots.</p>

<h2 data-step="02">Discovery</h2>
<p>Register a reviewed Spring Bean or use Java <code>ServiceLoader</code> metadata when the host supports it. Plugin IDs must be stable, bounded and safe for public inventory. Never perform remote calls or business side effects in contribution getters.</p>

<h2 data-step="03">Verification</h2>
<ul><li>Unit-test plugin decisions.</li><li>Run SPI contract tests for each adapter.</li><li>Start the matching Starter context.</li><li>Use Doctor to cover missing configuration and remote failure.</li><li>Verify the public plugin inventory remains redacted.</li></ul>
