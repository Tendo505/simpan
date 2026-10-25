plugins {
    java
    application
}

group = "io.github.tendo505"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Only for tests: a reference database to check query results against, and the
    // official PostgreSQL driver to check the wire protocol with a real client.
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("org.postgresql:postgresql:42.7.7")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

application {
    mainClass.set("io.github.tendo505.simpan.Main")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("failed")
        showStandardStreams = true // fuzzers and benchmarks print a one-line summary
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
