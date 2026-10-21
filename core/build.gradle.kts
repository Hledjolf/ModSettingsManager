// Game-agnostic plain Java: sidecar parsing, control inference, the JSON reader and writer, change diffing,
// the settings controller and the window layout. No game API is ever on this project's classpath.
tasks.named<ProcessResources>("processResources") {
    // The published schema ships inside the jar, so the sidecar loader validates against the same file.
    from(rootProject.file("schema")) {
        include("settings-sidecar.schema.json")
        into("modsettings")
    }
}
