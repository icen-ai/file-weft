plugins {
    id("fileweft.jvm17-library")
}

dependencies {
    api(project(":flowweft-agent-web-runtime"))
    api(libs.spring.boot3.starter.web)
    constraints {
        api(libs.tomcat.embed.core) {
            version { strictly(libs.versions.tomcat.embed.get()) }
            because("The Agent Web starter must retain the repository's security-patched Tomcat baseline")
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
            because("The Agent Web starter must retain the repository's security-patched Logback baseline")
        }
        api(libs.logback.core) {
            version { strictly(libs.versions.logback.get()) }
            because("Logback classic and core must remain on one security-patched version")
        }
    }
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
    testRuntimeOnly(libs.junit.platform.launcher)
}
