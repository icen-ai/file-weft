---
route: "project/security"
group: "project"
order: 2
locale: "en"
nav: "Security"
title: "Report security issues privately"
lead: "Do not disclose a suspected vulnerability in a public issue. Send a minimal, reproducible report to support@icen.ai and allow time for coordinated remediation."
format: "html"
---

<h2 data-step="01">What to include</h2>
<ul><li>Affected FileWeft version and module.</li><li>Deployment assumptions and required privileges.</li><li>Minimal reproduction steps or proof of concept.</li><li>Observed impact and any known mitigation.</li><li>A safe contact for coordinated follow-up.</li></ul>

<h2 data-step="02">Protect sensitive evidence</h2>
<p>Remove production credentials, tenant data, user information, object keys and private endpoints. Use synthetic fixtures whenever possible. Never send a live access token in an issue, log excerpt or browser recording.</p><aside class="callout" data-mark="@"><div><strong>Security contact</strong><p>Email support@icen.ai. General usage questions may use the same address, but suspected vulnerabilities should be clearly marked private and security-sensitive.</p></div></aside>
