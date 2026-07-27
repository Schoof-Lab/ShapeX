plugins {
    // Creates a shadow/fat jar bundle for non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"

    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

val shapexVersion = "0.1.0"

// Ensure shadowJar includes runtime dependencies and uses the drag-into-QuPath filename.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName.set("QuPath.ShapeX.extension.v$shapexVersion.jar")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:all")
}

// QuPath 0.7 is built for Java 25, so compile the extension with the same toolchain.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM) // Eclipse Temurin
    }
}

// QuPath extension info
qupathExtension {
    name = "QuPath ShapeX extension"
    group = "io.github.schooflab"
    version = shapexVersion
    description = "Design plates and export QuPath annotations for laser microdissection and spatial workflows"
    automaticModule = "io.github.schooflab.shapex"
}

// Define dependencies
dependencies {

    // Main dependencies
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)

    // Excel report (Apache POI)
    implementation("org.apache.poi:poi-ooxml:5.5.1")

    // anndata H5AD export (pure Java HDF5 writer)
    implementation("io.jhdf:jhdf:0.12.0")

    testImplementation(libs.qupath.core)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}
