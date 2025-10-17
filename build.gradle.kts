import org.gradle.jvm.tasks.Jar
import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("java")
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
group = "org.example"
version = "1.0"

repositories {
    mavenCentral()
}

// keep your existing deps EXACTLY as you have them
dependencies {
    implementation("org.telegram:telegrambots-longpolling:9.2.0")
    implementation("org.telegram:telegrambots-client:9.2.0")
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks.withType<Jar> {
    // executable jar
    manifest {
        attributes["Main-Class"] = "org.example.EchoBot"
    }
    // make a fat/uber jar
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}
