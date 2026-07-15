dependencies {
    add("testImplementation", platform("org.junit:junit-bom:5.11.4"))
    add("testImplementation", "ai.icen:flowweft-agent-testkit:${rootProject.version}")
    add("testImplementation", "ai.icen:flowweft-retrieval-testkit:${rootProject.version}")
    add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
}
