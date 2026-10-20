rootProject.name = "ModSettingsManager"

// Test fixtures: tiny plugins with a config (and a sidecar) that the headless scenarios run against.
// They are never shipped. Each fixtures/<name> with a build file is the project :fixture-<name>.
file("fixtures").listFiles()?.filter { File(it, "build.gradle.kts").exists() }?.sortedBy { it.name }?.forEach {
    include(":fixture-${it.name}")
    project(":fixture-${it.name}").projectDir = it
}
