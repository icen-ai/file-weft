---
route: "getting-started/installation"
group: "getting-started"
order: 2
locale: "en"
nav: "Installation"
title: "Install the 0.0.1 line"
lead: "Published artifacts use Maven group ai.icen and JVM package ai.icen.fw. Choose matching Spring Boot generations; never combine Boot 2 and Boot 3 starters."
format: "html"
---

<h2 data-step="01">Maven coordinates</h2>
<div class="code-block"><div class="code-label"><span>Kotlin / Gradle</span></div><pre><code>repositories {
    mavenCentral()
    maven { url = uri("https://maven.cnb.cool/china.ai/maven/-/packages/") }
}

dependencies {
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.1")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.1")
}</code></pre></div><p>For contract-only integrations, use <code>ai.icen:fileweft-spi:0.0.1</code>. Boot 2 applications replace both <code>boot3</code> artifact names with <code>boot2</code>.</p>

<h2 data-step="02">Runtime requirements</h2>
<ul><li>Build FileWeft with JDK 17 or newer; the verified build environment is JDK 21.</li><li>Baseline modules publish Java 8-compatible bytecode.</li><li>Spring Boot 3 starters require Java 17; Boot 2 starters retain the Java 8 baseline.</li></ul><aside class="callout warning" data-mark="!"><div><strong>Do not use withdrawn coordinates</strong><p>The early com.fileweft:*:0.0.1 trial coordinates are not a supported release line.</p></div></aside>

<h2 data-step="03">Verify the dependency</h2>
<div class="code-block"><div class="code-label"><span>PowerShell</span></div><pre><code>.\gradlew.bat dependencyInsight --dependency fileweft-spi --configuration runtimeClasspath</code></pre></div>
