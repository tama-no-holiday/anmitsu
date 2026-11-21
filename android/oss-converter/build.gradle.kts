plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }

// Pinned MIT snapshot (see third_party/kotlin-kana-kanji-converter/source-lock.json) with recorded TokenArray patches (PATCHES.md).
// Its own Gradle/download scripts are never executed; only these sources are compiled here.
val upstreamDir = rootProject.file("../third_party/kotlin-kana-kanji-converter/upstream")

kotlin.sourceSets.getByName("main").kotlin.apply {
    srcDir(upstreamDir.resolve("src/main/kotlin"))
    // Both need upstream Constants/resources (custom vocabulary, single-kanji data) that are deliberately not pinned.
    exclude("dictionary/DicUtils.kt", "single_kanji/**")
}

dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test {
    testLogging { events("failed", "skipped") }
    maxHeapSize = "512m"
}

tasks.jar {
    from(upstreamDir.resolve("LICENSE")) {
        into("META-INF")
        rename { "LICENSE-kotlin-kana-kanji-converter" }
    }
}

// Usage: gradlew :oss-converter:ossCli --args="build --mozc-dir <dir> --out <dir>" (also: demo, bench).
// Relative paths resolve from the repository root. Heap: -PossHeap=2g (default).
tasks.register<JavaExec>("ossCli") {
    group = "application"
    description = "Build, demo or benchmark the experimental OSS converter resources (no network)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.astra.ime.oss.OssCliKt")
    workingDir = rootProject.file("..")
    maxHeapSize = (findProperty("ossHeap") as String?) ?: "2g"
}
