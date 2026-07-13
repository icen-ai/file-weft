---
route: "guides/spring-boot"
group: "guides"
order: 1
locale: "zh"
nav: "Spring Boot 宿主"
title: "安全装配 Spring Boot"
lead: "运行时与 Web Starter 都是加法适配器。认证、网关策略、DataSource 所有权和能力选择仍由宿主负责。"
format: "html"
---

<h2 data-step="01">匹配 Boot 代际</h2>
<table class="comparison-table"><thead><tr><th>宿主</th><th>运行时</th><th>HTTP</th></tr></thead><tbody><tr><td>Spring Boot 3 / Java 17+</td><td>fileweft-spring-boot3-starter</td><td>fileweft-web-spring-boot3-starter</td></tr><tr><td>Spring Boot 2.7 / Java 8+</td><td>fileweft-spring-boot2-starter</td><td>fileweft-web-spring-boot2-starter</td></tr></tbody></table><p>不能同时安装两个代际。Web Starter 不会隐式引入持久化，也不替代运行时 Starter。</p>

<h2 data-step="02">保护入口</h2>
<p>将已验证身份绑定到租户、用户和授权 SPI。CORS、CSRF、OAuth/OIDC、mTLS、上传限制、超时与限流由宿主或网关配置。FileWeft 不提供弱默认认证。</p>
