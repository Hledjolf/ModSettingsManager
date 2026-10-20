// Mod Settings Manager: one plugin jar (the root project) plus test fixtures under fixtures/.
// The server jar is the API: compile-only, never bundled. Its path is per machine (hytaleServerJar property).
val hytaleServerJar = (findProperty("hytaleServerJar") as String?)
    ?: "${System.getenv("APPDATA")}/Hytale/install/release/package/game/latest/Server/HytaleServer.jar"

version = "1.0.0"

allprojects {
    apply(plugin = "java")
    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    dependencies {
        "compileOnly"(files(hytaleServerJar))
    }

    tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

    // Every plugin jar goes to build/mods, so the scenario script can load them together.
    tasks.named<Jar>("jar") {
        destinationDirectory.set(rootProject.layout.buildDirectory.dir("mods"))
    }
}

dependencies {
    // Unit tests cover the pure packages only; they never load Hytale classes.
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("modsettings.repo", projectDir.absolutePath)
}

tasks.named<ProcessResources>("processResources") {
    // The published schema ships inside the jar, so the sidecar loader validates against the same file.
    from("schema") {
        include("settings-sidecar.schema.json")
        into("modsettings")
    }
    inputs.property("version", project.version)
    filesMatching("manifest.json") { expand("version" to project.version) }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("ModSettingsManager")
    from("LICENSE")
}

// ---------------------------------------------------------------------------------------------------------
// checkAdapterBoundary: the Hytale API may be named only in modsettings.adapter and the plugin main class
// (modsettings/ModSettingsPlugin.java). A Hytale update then breaks one package. Fixtures are test plugins
// and are exempt.
// ---------------------------------------------------------------------------------------------------------
val checkAdapterBoundary by tasks.registering {
    group = "verification"
    description = "Fails if code outside modsettings.adapter and ModSettingsPlugin uses the Hytale API."
    val sources = fileTree("src/main/java") { include("**/*.java") }
    inputs.files(sources)
    doLast {
        val offenders = sources.files.filter { f ->
            val p = f.invariantSeparatorsPath
            !p.contains("/modsettings/adapter/") && !p.endsWith("/modsettings/ModSettingsPlugin.java") &&
                f.readLines().any { it.contains("com.hypixel") }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("Hytale API used outside modsettings.adapter:\n" + offenders.joinToString("\n") { " - " + it.relativeTo(projectDir) })
        }
    }
}

// The jar must never carry Hytale classes, the server jar, assets copied from Assets.zip or decompiled code.
val checkJarContents by tasks.registering {
    group = "verification"
    description = "Fails if the plugin jar contains Hytale classes, source files or anything outside the allow list."
    dependsOn(tasks.named("jar"))
    val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    doLast {
        val allowed = Regex("""META-INF/.*|manifest\.json|LICENSE|modsettings/.*\.class|modsettings/settings-sidecar\.schema\.json|Common/UI/Custom/ModSettings/[A-Za-z]+\.ui|Settings/ModSettingsManager_ModSettingsManager\.settings\.json""")
        val bad = java.util.zip.ZipFile(jarFile.get().asFile).use { zf ->
            zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.filter { !allowed.matches(it) }.toList()
        }
        if (bad.isNotEmpty()) throw GradleException("Unexpected files in the jar:\n" + bad.joinToString("\n") { " - $it" })
    }
}

tasks.named("check") {
    dependsOn(checkAdapterBoundary)
    dependsOn(checkJarContents)
}

// Fixtures compile against the mod itself (their scenarios drive its controller).
subprojects {
    dependencies {
        "compileOnly"(rootProject)
    }
    tasks.named<Jar>("jar") {
        archiveBaseName.set(project.name)
        archiveVersion.set("")
    }
}

// ---------------------------------------------------------------------------------------------------------
// release: build/release/ModSettingsManager-<version>.jar and ModSettingsManager-<version>.zip (the jar plus
// README.md, LICENSE, CHANGELOG.md, docs/SIDECAR.md and the schema). Runs check first and verifies the jar:
// no fixture or test classes, no Hytale classes, the manifest version matching the build.
// ---------------------------------------------------------------------------------------------------------
val releaseDir = layout.buildDirectory.dir("release")

val releaseJar by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Copies the checked plugin jar to build/release."
    dependsOn(tasks.named("check"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    into(releaseDir)
    doLast {
        val jar = releaseDir.get().file("ModSettingsManager-${project.version}.jar").asFile
        java.util.zip.ZipFile(jar).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toList()
            val bad = names.filter { it.startsWith("msmtest/") || it.startsWith("com/hypixel/") || it.contains("Test") && it.endsWith(".class") || it.endsWith(".java") }
            if (bad.isNotEmpty()) throw GradleException("Release jar contains fixture, test or Hytale files: $bad")
            val manifest = String(zf.getInputStream(zf.getEntry("manifest.json")).readAllBytes(), Charsets.UTF_8)
            if (!manifest.contains("\"Version\": \"${project.version}\"")) throw GradleException("manifest.json version does not match ${project.version}")
        }
    }
}

val release by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds build/release/ModSettingsManager-<version>.jar and .zip."
    dependsOn(releaseJar)
    archiveFileName.set("ModSettingsManager-${project.version}.zip")
    destinationDirectory.set(releaseDir)
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    from(".") { include("README.md", "LICENSE", "CHANGELOG.md") }
    from("docs") { include("SIDECAR.md"); into("docs") }
    from("schema") { include("settings-sidecar.schema.json"); into("schema") }
    doLast { logger.lifecycle("Release: " + releaseDir.get().asFile) }
}
