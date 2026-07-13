---
route: "guides/spring-boot"
group: "guides"
order: 1
locale: "en"
nav: "Spring Boot hosting"
title: "Assemble Spring Boot safely"
lead: "The runtime and Web starters are additive adapters. Your host remains responsible for authentication, gateway policy, DataSource ownership and explicit capability selection."
format: "html"
---

<h2 data-step="01">Match the generation</h2>
<table class="comparison-table"><thead><tr><th>Host</th><th>Runtime</th><th>HTTP</th></tr></thead><tbody><tr><td>Spring Boot 3 / Java 17+</td><td>fileweft-spring-boot3-starter</td><td>fileweft-web-spring-boot3-starter</td></tr><tr><td>Spring Boot 2.7 / Java 8+</td><td>fileweft-spring-boot2-starter</td><td>fileweft-web-spring-boot2-starter</td></tr></tbody></table><p>Do not install both generations. Web starters do not implicitly add persistence or replace runtime starters.</p>

<h2 data-step="02">Secure the edge</h2>
<p>Bind your verified authentication to the tenant, user and authorization SPIs. Configure CORS, CSRF, OAuth/OIDC, mTLS, upload limits, timeout and rate limiting in the host or gateway. FileWeft deliberately supplies no weak default authentication.</p>
