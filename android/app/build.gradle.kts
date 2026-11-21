import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
}
// scripts/build-android.ps1 sets ASTRA_TOOLCHAINS from -ToolchainRoot (dedicated worktrees use the shared one).
val toolchains = System.getenv("ASTRA_TOOLCHAINS")?.let { file(it) } ?: rootProject.file("../.astra/local/toolchains")
android {
    namespace = "dev.astra.ime"
    compileSdk = 36
    defaultConfig {
        // 1.0.0: a new package, so it installs beside dev.astra.ime.trial (0.1.x) instead of updating it.
        applicationId = "io.github.tama_no_holiday.anmitsu"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        // No signing config: assembleRelease gives app-release-unsigned.apk; the user signs it with their own key.
        // R8 has never been checked against the JNI and memory-mapping paths, so it stays off.
        getByName("release") { isMinifyEnabled = false }
    }
    // The GGUF is copied to app storage once and memory-mapped by llama.cpp; storing it uncompressed saves inflating.
    // lexicon.bin (OSS dictionary) is memory-mapped straight from the APK, which needs it uncompressed.
    androidResources { noCompress += listOf("gguf", "bin") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // EditorPolicy tests use only EditorInfo fields/constants, never mocked editor behavior.
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("maven.repo.local", toolchains.resolve("maven").absolutePath)
        }
    }
    // Stable, local debug key: retain .astra/local/toolchains/android-user/debug.keystore for updates.
    signingConfigs.getByName("debug") {
        storeFile = toolchains.resolve("android-user/debug.keystore")
    }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

/**
 * Generated assets: OSS notices (always, since :oss-converter code is compiled in) and, when
 * ASTRA_OSS_RESOURCES names a directory built by `ossCli build`, the compiled Mozc resources.
 * Each resource must match oss-assets.lock or the build fails; the lock is shipped for the runtime check.
 */
abstract class PrepareOssAssets : DefaultTask() {
    @get:InputFile abstract val lock: RegularFileProperty
    @get:InputFiles abstract val licenses: ConfigurableFileCollection
    @get:Optional @get:InputDirectory abstract val resources: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val licenseDir = File(out, "licenses").apply { mkdirs() }
        licenses.asFileTree.forEach { it.copyTo(File(licenseDir, it.name)) }
        val source = resources.orNull?.asFile ?: return
        val ossDir = File(out, "oss").apply { mkdirs() }
        val lockFile = lock.get().asFile
        for (line in lockFile.readLines().filter { it.isNotBlank() }) {
            val (sha, name) = line.trim().split(Regex("\\s+"))
            val file = File(source, name)
            check(file.isFile) { "OSS resource missing: $name" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == sha) { "OSS resource $name does not match oss-assets.lock" }
            file.copyTo(File(ossDir, name))
        }
        lockFile.copyTo(File(ossDir, "oss-assets.sha256"))
    }
}

val licenseCopies = layout.buildDirectory.dir("intermediates/ossLicenseNames")
val renameLicenses by tasks.registering(Copy::class) {
    into(licenseCopies)
    from(rootProject.file("../third_party/mozc/LICENSE")) { rename { "Mozc-LICENSE.txt" } }
    from(rootProject.file("../third_party/mozc/DICTIONARY-NOTICES.txt")) { rename { "Mozc-DICTIONARY-NOTICES.txt" } }
    from(rootProject.file("../third_party/kotlin-kana-kanji-converter/upstream/LICENSE")) {
        rename { "kotlin-kana-kanji-converter-LICENSE.txt" }
    }
    from(rootProject.file("../third_party/kotlin-kana-kanji-converter/PATCHES.md")) {
        rename { "kotlin-kana-kanji-converter-PATCHES.md" }
    }
    from(rootProject.file("../third_party/zenz/llama.cpp-LICENSE.txt"))
    from(rootProject.file("../third_party/zenz/llama.cpp-LICENSE-jsonhpp.txt"))
    from(rootProject.file("../third_party/zenz/Apache-2.0.txt")) { rename { "zenz-LICENSE-Apache-2.0.txt" } }
    from(rootProject.file("../third_party/sudachidict/LEGAL")) { rename { "SudachiDict-LEGAL.txt" } }
    from(rootProject.file("../third_party/sudachidict/LICENSE-2.0.txt")) { rename { "SudachiDict-LICENSE-2.0.txt" } }
    from(rootProject.file("../third_party/mozc-ut/neologd/COPYING")) { rename { "mecab-ipadic-neologd-COPYING.txt" } }
}

/**
 * Optional zenz judge: ASTRA_ZENZ_LIB_DIR holds <abi>/libastra_zenz.so built from android/zenz-native and
 * ASTRA_ZENZ_MODEL the GGUF. Both must match zenz-assets.lock or the build fails; without them nothing is added.
 * The library goes to assets/zenz/<abi>/, not lib/: an APK whose only native code is arm64 would not install on other
 * ABIs. The app copies it to private storage and loads it only after checking the CPU (ZenzBackend, ZenzCpu).
 */
abstract class PrepareZenz : DefaultTask() {
    @get:InputFile abstract val lock: RegularFileProperty
    @get:Optional @get:InputDirectory abstract val libDir: DirectoryProperty
    @get:Optional @get:InputFile abstract val model: RegularFileProperty
    @get:OutputDirectory abstract val assetDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val assets = assetDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val libs = libDir.orNull?.asFile ?: return
        val modelFile = model.orNull?.asFile ?: return
        val expected = lock.get().asFile.readLines().filter { it.isNotBlank() }
            .associate { line -> line.trim().split(Regex("\\s+")).let { it[1] to it[0] } }
        fun verify(file: File, name: String) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expected[name]) { "$name does not match zenz-assets.lock" }
        }
        val zenzAssets = File(assets, "zenz").apply { mkdirs() }
        val libraries = expected.keys.filter { it.endsWith(".so") }
        check(libraries.isNotEmpty()) { "zenz-assets.lock lists no library" }
        for (name in libraries) {
            val so = File(libs, name)
            verify(so, name)
            so.copyTo(File(zenzAssets, name))
        }
        verify(modelFile, modelFile.name)
        modelFile.copyTo(File(zenzAssets, modelFile.name))
        // The app checks its copies of both against these lines before it maps or loads them.
        File(zenzAssets, "zenz-assets.sha256").writeText(
            (libraries + modelFile.name).joinToString("") { "${expected.getValue(it)}  $it\n" },
        )
    }
}
val prepareZenz by tasks.registering(PrepareZenz::class) {
    lock.set(file("zenz-assets.lock"))
    System.getenv("ASTRA_ZENZ_LIB_DIR")?.takeIf { it.isNotBlank() }?.let { libDir.set(file(it)) }
    System.getenv("ASTRA_ZENZ_MODEL")?.takeIf { it.isNotBlank() }?.let { model.set(file(it)) }
    assetDir.set(layout.buildDirectory.dir("generated/zenzAssets"))
}
val prepareOssAssets by tasks.registering(PrepareOssAssets::class) {
    lock.set(file("oss-assets.lock"))
    licenses.from(renameLicenses)
    System.getenv("ASTRA_OSS_RESOURCES")?.takeIf { it.isNotBlank() }?.let { resources.set(file(it)) }
    outputDir.set(layout.buildDirectory.dir("generated/ossAssets"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareOssAssets, PrepareOssAssets::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(prepareZenz, PrepareZenz::assetDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":oss-converter"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
