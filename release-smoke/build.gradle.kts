import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.21" apply false
}

version = providers.gradleProperty("fileweftVersion").orNull
    ?: throw GradleException("-PfileweftVersion is required for release consumer smoke testing.")

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "ai.icen.release.smoke"
    version = rootProject.version
}

fun Project.targetJvm(javaVersion: JavaVersion, kotlinTarget: JvmTarget, release: Int) {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(release)
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(kotlinTarget)
        compilerOptions.javaParameters.set(true)
    }
}

project(":boot2-consumer") {
    targetJvm(JavaVersion.VERSION_1_8, JvmTarget.JVM_1_8, 8)
    dependencies {
        add("implementation", "ai.icen:fileweft-spring-boot2-starter:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-web-spring-boot2-starter:${rootProject.version}")
    }
}

project(":boot3-consumer") {
    targetJvm(JavaVersion.VERSION_17, JvmTarget.JVM_17, 17)
    dependencies {
        add("implementation", "ai.icen:fileweft-spring-boot3-starter:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-web-spring-boot3-starter:${rootProject.version}")
    }
}

project(":library-consumer") {
    targetJvm(JavaVersion.VERSION_1_8, JvmTarget.JVM_1_8, 8)
    dependencies {
        add("implementation", "ai.icen:fileweft-spi:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-agent:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-persistence:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-adapter-micrometer:${rootProject.version}")
    }
}
