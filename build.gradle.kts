plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

group = "io.github.shengboj0324"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val anthropicVersion = "3.2.0"

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    // Bundled into the jar (shadowJar) so the plugin never depends on a Maven mirror at server start-up.
    implementation("com.anthropic:anthropic-java:$anthropicVersion")

    testImplementation("io.papermc.paper:paper-api:26.3.build.+")
    testImplementation("com.anthropic:anthropic-java:$anthropicVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

tasks.jar {
    archiveBaseName.set("ClaudeArchitect")
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("ClaudeArchitect")
    archiveClassifier.set("")
    // keep the bundled HTTP and JSON libraries private to this plugin
    val lib = "io.github.shengboj0324.architect.lib"
    relocate("okhttp3", "$lib.okhttp3")
    relocate("okio", "$lib.okio")
    relocate("com.fasterxml.jackson", "$lib.jackson")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "META-INF/native-image/**")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
