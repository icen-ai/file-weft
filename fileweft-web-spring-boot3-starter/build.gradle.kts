plugins {
    id("fileweft.jvm17-library")
}

dependencies {
    api(project(":fileweft-web-runtime"))
    api(libs.spring.boot3.starter.web)
    constraints {
        api(libs.tomcat.embed.core) {
            version { strictly(libs.versions.tomcat.embed.get()) }
            because("Tomcat 10.1.57 contains the July 2026 security fixes missing from Spring Boot 3.5.16's managed 10.1.55 baseline")
        }
        api(libs.tomcat.embed.el) {
            version { strictly(libs.versions.tomcat.embed.get()) }
            because("Every embedded Tomcat component must remain on one security-patched version")
        }
        api(libs.tomcat.embed.websocket) {
            version { strictly(libs.versions.tomcat.embed.get()) }
            because("Every embedded Tomcat component must remain on one security-patched version")
        }
        api(libs.logback.classic) {
            version { strictly(libs.versions.logback.get()) }
            because("Logback 1.5.38 contains security fixes missing from Spring Boot 3.5.16's managed baseline")
        }
        api(libs.logback.core) {
            version { strictly(libs.versions.logback.get()) }
            because("Logback classic and core must remain on one security-patched version")
        }
    }
    // Gradle constraints are preserved in module metadata, but Maven
    // dependencyManagement is not transitive. Direct runtime dependencies
    // keep plain-POM consumers on the same patched embedded runtime.
    runtimeOnly(libs.tomcat.embed.core) {
        exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
    }
    runtimeOnly(libs.tomcat.embed.el)
    runtimeOnly(libs.tomcat.embed.websocket) {
        exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
    }
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logback.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot3.test)
    testImplementation(libs.spring.boot3.starter.test)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
