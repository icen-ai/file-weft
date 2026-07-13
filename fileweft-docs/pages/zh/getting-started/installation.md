---
route: "getting-started/installation"
group: "getting-started"
order: 2
locale: "zh"
nav: "安装"
title: "安装 0.0.1 正式版"
lead: "正式制品使用 Maven group ai.icen，JVM 包名为 ai.icen.fw。Spring Boot 代际必须匹配，不能混用 Boot 2 与 Boot 3 Starter。"
format: "html"
---

<h2 data-step="01">Maven 坐标</h2>
<div class="code-block"><div class="code-label"><span>Kotlin / Gradle</span></div><pre><code>repositories {
    mavenCentral()
    maven { url = uri("https://maven.cnb.cool/china.ai/maven/-/packages/") }
}

dependencies {
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.1")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.1")
}</code></pre></div><p>只接入契约时使用 <code>ai.icen:fileweft-spi:0.0.1</code>。Boot 2 应用将两个制品名中的 <code>boot3</code> 换成 <code>boot2</code>。</p>

<h2 data-step="02">运行要求</h2>
<ul><li>构建 FileWeft 使用 JDK 17+，当前验证环境为 JDK 21。</li><li>基础模块发布 Java 8 兼容字节码。</li><li>Spring Boot 3 Starter 需要 Java 17；Boot 2 Starter 保持 Java 8 基线。</li></ul><aside class="callout warning" data-mark="!"><div><strong>不要使用已撤回坐标</strong><p>早期 com.fileweft:*:0.0.1 试推坐标不属于受支持发布线。</p></div></aside>

<h2 data-step="03">验证依赖</h2>
<div class="code-block"><div class="code-label"><span>PowerShell</span></div><pre><code>.\gradlew.bat dependencyInsight --dependency fileweft-spi --configuration runtimeClasspath</code></pre></div>
