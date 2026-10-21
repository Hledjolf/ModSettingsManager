// Mod Settings Manager: a game-agnostic core plus one adapter per game.
// core/ is plain Java; adapters/hytale/ is the Hytale plugin, which merges core into its single jar.
// Test fixtures under fixtures/ are local plugins for the headless scenarios and are never shipped.
plugins { base }

version = "1.0.1"
val modVersion = version.toString()

val hytaleServerJar = (findProperty("hytaleServerJar") as String?)
    ?: "${System.getenv("APPDATA")}/Hytale/install/release/package/game/latest/Server/HytaleServer.jar"

subprojects {
    version = rootProject.version
    apply(plugin = "java")
    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

    if (path == ":core" || path.startsWith(":adapters")) {
        dependencies {
            "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.11.4")
        }
        tasks.withType<Test> {
            useJUnitPlatform()
            systemProperty("modsettings.repo", rootProject.projectDir.absolutePath)
        }
    }

    if (name.startsWith("fixture-")) {
        // Fixtures compile against the server jar, the core and the Hytale adapter (their scenarios drive the controller).
        dependencies {
            "compileOnly"(files(hytaleServerJar))
            "compileOnly"(project(":core"))
            "compileOnly"(project(":adapters:hytale"))
        }
        tasks.named<Jar>("jar") {
            archiveBaseName.set(project.name)
            archiveVersion.set("")
            destinationDirectory.set(rootProject.layout.buildDirectory.dir("mods"))
        }
    }
}

val hytaleJar = project(":adapters:hytale").tasks.named<Jar>("jar")

// ---------------------------------------------------------------------------------------------------------
// checkAdapterBoundary: core/ is game-agnostic. No file under core/ may name a game API. Adapters may.
// ---------------------------------------------------------------------------------------------------------
val checkAdapterBoundary = tasks.register("checkAdapterBoundary") {
    group = "verification"
    description = "Fails if any file under core/ uses the Hytale API."
    val sources = fileTree("core") { include("**/*.java"); exclude("build/**") }
    inputs.files(sources)
    doLast {
        val offenders = sources.files.filter { f -> f.readLines().any { it.contains("com.hypixel") } }
        if (offenders.isNotEmpty()) {
            throw GradleException("Hytale API used in core:\n" + offenders.joinToString("\n") { " - " + it.relativeTo(projectDir) })
        }
    }
}

// The jar must never carry Hytale classes, the server jar, assets copied from Assets.zip or decompiled code.
val checkJarContents = tasks.register("checkJarContents") {
    group = "verification"
    description = "Fails if the plugin jar contains Hytale classes, source files or anything outside the allow list."
    dependsOn(hytaleJar)
    val jarFile = hytaleJar.flatMap { it.archiveFile }
    doLast {
        val allowed = Regex("""META-INF/.*|manifest\.json|LICENSE|modsettings/.*\.class|modsettings/settings-sidecar\.schema\.json|Common/UI/Custom/ModSettings/[A-Za-z]+\.ui|Settings/ModSettingsManager_ModSettingsManager\.settings\.json""")
        val names = java.util.zip.ZipFile(jarFile.get().asFile).use { zf ->
            zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        }
        val bad = names.filter { !allowed.matches(it) }
        if (bad.isNotEmpty()) throw GradleException("Unexpected files in the jar:\n" + bad.joinToString("\n") { " - $it" })
        val required = listOf("modsettings/app/SettingsController.class", "modsettings/model/SidecarParser.class",
            "modsettings/settings-sidecar.schema.json", "modsettings/ModSettingsPlugin.class")
        required.filter { it !in names }.forEach { throw GradleException("The jar is missing $it") }
    }
}

// ---------------------------------------------------------------------------------------------------------
// checkNoProjectMarks: this mod is a standalone product. No tracked, shipped or page-kit text may name the
// author's other projects. The words are assembled from parts so this file does not match itself.
// ---------------------------------------------------------------------------------------------------------
val checkNoProjectMarks = tasks.register("checkNoProjectMarks") {
    group = "verification"
    description = "Fails if a tracked or shipped file names another project of the author."
    dependsOn(hytaleJar)
    val jarFile = hytaleJar.flatMap { it.archiveFile }
    val roots = listOf("core/src/main", "adapters/hytale/src/main", "schema", "gradle", "docs/page")
    val singles = listOf("README.md", "CHANGELOG.md", "LICENSE", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
        ".gitignore", ".gitattributes", "docs/SIDECAR.md", "core/build.gradle.kts", "adapters/hytale/build.gradle.kts")
    doLast {
        val words = listOf("arch" + "mage", "spell" + "bound", "master" + " of " + "magic")
        val pattern = Regex(words.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        val textExt = setOf("java", "json", "md", "txt", "bbcode", "kts", "properties", "ui", "gradle", "sh", "py", "")
        val files = singles.map { File(projectDir, it) }.filter { it.isFile } +
            roots.flatMap { r -> File(projectDir, r).walkTopDown().filter { it.isFile && it.extension.lowercase() in textExt }.toList() }
        val hits = mutableListOf<String>()
        for (f in files) {
            if (f.extension == "jar") continue
            val text = f.readText(Charsets.UTF_8)
            if (pattern.containsMatchIn(text)) hits += f.relativeTo(projectDir).invariantSeparatorsPath
        }
        java.util.zip.ZipFile(jarFile.get().asFile).use { zf ->
            zf.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                val bytes = zf.getInputStream(e).readAllBytes()
                if (pattern.containsMatchIn(String(bytes, Charsets.ISO_8859_1)) || pattern.containsMatchIn(e.name)) hits += "jar:" + e.name
            }
        }
        if (hits.isNotEmpty()) throw GradleException("Names of other projects found (not allowed in a standalone mod):\n" + hits.joinToString("\n") { " - $it" })
    }
}

tasks.named("check") {
    dependsOn(subprojects.filter { it.path == ":core" || it.path.startsWith(":adapters") }.map { it.tasks.named("check") })
    dependsOn(checkAdapterBoundary)
    dependsOn(checkJarContents)
    dependsOn(checkNoProjectMarks)
}

// ---------------------------------------------------------------------------------------------------------
// release: build/release/ModSettingsManager-<version>.jar and ModSettingsManager-<version>.zip (the jar plus
// README.md, LICENSE, CHANGELOG.md, docs/SIDECAR.md and the schema). Runs check first and verifies the jar:
// no fixture or test classes, no Hytale classes, the manifest version matching the build.
// ---------------------------------------------------------------------------------------------------------
val releaseDir = layout.buildDirectory.dir("release")

val releaseJar = tasks.register<Copy>("releaseJar") {
    group = "distribution"
    description = "Copies the checked plugin jar to build/release."
    dependsOn(tasks.named("check"))
    from(hytaleJar.flatMap { it.archiveFile })
    into(releaseDir)
    doLast {
        val jar = releaseDir.get().file("ModSettingsManager-$modVersion.jar").asFile
        java.util.zip.ZipFile(jar).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toList()
            val bad = names.filter { it.startsWith("msmtest/") || it.startsWith("com/hypixel/") || it.contains("Test") && it.endsWith(".class") || it.endsWith(".java") }
            if (bad.isNotEmpty()) throw GradleException("Release jar contains fixture, test or Hytale files: $bad")
            val manifest = String(zf.getInputStream(zf.getEntry("manifest.json")).readAllBytes(), Charsets.UTF_8)
            if (!manifest.contains("\"Version\": \"$modVersion\"")) throw GradleException("manifest.json version does not match $modVersion")
        }
    }
}

val release = tasks.register<Zip>("release") {
    group = "distribution"
    description = "Builds build/release/ModSettingsManager-<version>.jar and .zip."
    dependsOn(releaseJar)
    archiveFileName.set("ModSettingsManager-$modVersion.zip")
    destinationDirectory.set(releaseDir)
    from(hytaleJar.flatMap { it.archiveFile })
    from(".") { include("README.md", "LICENSE", "CHANGELOG.md") }
    from("docs") { include("SIDECAR.md"); into("docs") }
    from("schema") { include("settings-sidecar.schema.json"); into("schema") }
    doLast { logger.lifecycle("Release: " + releaseDir.get().asFile) }
}
