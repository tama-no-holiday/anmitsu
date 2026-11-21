package dev.astra.ime

import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import dev.astra.ime.ConversionWorker.OssState
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 1.0.0: the release build keeps every feature, and zenz's native code is reached only on a CPU that can run it. */
class ReleaseBuildTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Copies are read-only; Windows will not delete those, so the folder is made writable again first. */
    @After fun writable() {
        temp.root.walkTopDown().forEach { it.setWritable(true) }
    }

    // --- CPU check ----------------------------------------------------------------------------------

    private fun auxv(vararg pairs: Pair<Long, Long>): ByteArray {
        val buffer = ByteBuffer.allocate((pairs.size + 1) * 16).order(ByteOrder.LITTLE_ENDIAN)
        for ((type, value) in pairs) buffer.putLong(type).putLong(value)
        buffer.putLong(0).putLong(0) // AT_NULL
        return buffer.array()
    }

    private val all = ZenzCpu.REQUIRED_HWCAP
    private val features = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"

    @Test fun aCpuReportingEveryFeatureIsSupported() {
        assertTrue(ZenzCpu.decide("aarch64", true, auxv(6L to 4096, 16L to all, 26L to 0), null))
        assertTrue(ZenzCpu.decide("aarch64", true, auxv(16L to (all or (1L shl 3))), null)) // extra bits are fine
        assertTrue(ZenzCpu.decide("arm64", true, auxv(16L to all), null))
    }

    @Test fun eachMissingFeatureIsUnsupportedAndAuxvIsNotOverruledByCpuinfo() {
        for (bit in 0 until 64) {
            val mask = 1L shl bit
            if ((all and mask) == 0L) continue
            assertFalse("bit $bit", ZenzCpu.decide("aarch64", true, auxv(16L to (all and mask.inv())), features))
        }
        // An Armv8.0 core (e.g. Cortex-A53): no dotprod, no fp16.
        val armv80 = (1L shl 0) or (1L shl 1) or (1L shl 7)
        assertFalse(ZenzCpu.decide("aarch64", true, auxv(16L to armv80), features))
    }

    @Test fun otherAbisAnd32BitProcessesAreUnsupportedWhateverTheyReport() {
        for (arch in listOf("x86_64", "x86", "armv7l", "armv8l", "riscv64", "amd64", "", null)) {
            assertFalse("$arch", ZenzCpu.decide(arch, true, auxv(16L to all), features))
        }
        assertFalse(ZenzCpu.decide("aarch64", false, auxv(16L to all), features))
    }

    @Test fun withoutAuxvEveryCpuinfoFeaturesLineMustListEverything() {
        assertTrue(ZenzCpu.decide("aarch64", true, null, "processor\t: 0\n$features\n\nprocessor\t: 1\n$features\n"))
        val littleCore = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid"
        assertFalse(ZenzCpu.decide("aarch64", true, null, "$features\n$littleCore\n"))
        assertFalse(ZenzCpu.decide("aarch64", true, auxv(6L to 4096), "$littleCore\n")) // auxv without AT_HWCAP
    }

    @Test fun anUnknownAnswerFailsClosed() {
        assertFalse(ZenzCpu.decide("aarch64", true, null, null))
        assertFalse(ZenzCpu.decide("aarch64", true, ByteArray(0), ""))
        assertFalse(ZenzCpu.decide("aarch64", true, ByteArray(7), "Hardware\t: something\n"))
        assertNull(ZenzCpu.hwcap(ByteArray(15)))
        // AT_NULL ends the vector: a later AT_HWCAP is not read.
        val ended = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).putLong(0).putLong(0).putLong(16).putLong(all).array()
        assertNull(ZenzCpu.hwcap(ended))
    }

    @Test fun theHostJvmIsNotTakenForAPhone() {
        // Not a 64-bit Android ARM process (android.os.Process is a stub here): the real check must say no.
        assertFalse(ZenzCpu.supported())
    }

    // --- Library and model copies ---------------------------------------------------------------------

    private val libraryBytes = "synthetic library".toByteArray()
    private val modelBytes = "synthetic model".toByteArray()
    private val modelName = "zenz-test.gguf"

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assets(library: ByteArray = libraryBytes, listed: String = sha(libraryBytes), withLibraryLine: Boolean = true): File {
        val dir = temp.newFolder()
        File(dir, ZenzBackend.LIBRARY).apply { parentFile.mkdirs() }.writeBytes(library)
        File(dir, modelName).writeBytes(modelBytes)
        val lines = (if (withLibraryLine) "$listed  ${ZenzBackend.LIBRARY}\n" else "") + "${sha(modelBytes)}  $modelName\n"
        File(dir, ZenzBackend.HASHES).writeText(lines)
        return dir
    }

    private val opened = ArrayList<String>()
    private val nativeCalls = ArrayList<Pair<String, String>>()

    private fun load(dir: File, storage: File, supported: Boolean = true) = ZenzBackend.load(
        open = { name -> opened += name; File(dir, name).takeIf { it.isFile }?.inputStream() ?: throw FileNotFoundException(name) },
        exists = { name -> File(dir, name).isFile },
        storage = storage,
        supported = { supported },
        openNative = { model, library -> nativeCalls += model to library; null },
    )

    private fun libraryCopy(storage: File) = File(storage, "${ZenzBackend.NATIVE_DIR}/libastra_zenz-${sha(libraryBytes).take(16)}.so")

    @Test fun anUnsupportedCpuCopiesAndLoadsNothing() {
        val storage = File(temp.root, "storage")
        val loaded = load(assets(), storage, supported = false)
        assertEquals(ZenzBackend.LoadState.UNSUPPORTED, loaded.state)
        assertNull(loaded.scorer)
        assertTrue(opened.isEmpty())
        assertTrue(nativeCalls.isEmpty())
        assertFalse(storage.exists())
    }

    @Test fun withoutTheBundleTheCpuIsNotEvenAsked() {
        var asked = false
        val loaded = ZenzBackend.load({ throw FileNotFoundException(it) }, { false }, File(temp.root, "s"), supported = { asked = true; true })
        assertEquals(ZenzBackend.LoadState.MISSING, loaded.state)
        assertFalse(asked)
    }

    @Test fun aSupportedCpuGetsVerifiedReadOnlyCopiesLoadedByAbsolutePath() {
        val storage = File(temp.root, "storage")
        load(assets(), storage)
        val library = libraryCopy(storage)
        val model = File(storage, modelName)
        assertEquals(listOf(model.absolutePath to library.absolutePath), nativeCalls)
        assertTrue(File(nativeCalls.single().second).isAbsolute)
        assertTrue(libraryBytes.contentEquals(library.readBytes()))
        assertTrue(modelBytes.contentEquals(model.readBytes()))
        assertFalse(library.canWrite())
        assertFalse(model.canWrite())
        assertTrue(storage.walkTopDown().none { it.name.endsWith(".tmp") })

        // Reused as they are: only the hash list is read from the APK again.
        opened.clear()
        load(assets(), storage)
        assertEquals(listOf(ZenzBackend.HASHES), opened)
    }

    @Test fun aLibraryNotMatchingItsHashIsNeverLoadedOrLeftBehind() {
        val storage = File(temp.root, "storage")
        val loaded = load(assets(library = "tampered".toByteArray()), storage)
        assertEquals(ZenzBackend.LoadState.FAILED, loaded.state)
        assertTrue(nativeCalls.isEmpty())
        assertTrue(File(storage, ZenzBackend.NATIVE_DIR).listFiles().orEmpty().isEmpty())
    }

    @Test fun aHashListWithoutTheLibraryFailsClosed() {
        val loaded = load(assets(withLibraryLine = false), File(temp.root, "storage"))
        assertEquals(ZenzBackend.LoadState.FAILED, loaded.state)
        assertTrue(nativeCalls.isEmpty())
    }

    @Test fun aDamagedCopyIsReplacedAndOtherVersionsAreRemoved() {
        val storage = File(temp.root, "storage")
        val library = libraryCopy(storage).apply { parentFile.mkdirs(); writeText("damaged") }
        val old = File(library.parentFile, "libastra_zenz-0123456789abcdef.so").apply { writeText("old version") }
        val abandoned = File(library.parentFile, "x.tmp").apply { writeText("half"); setLastModified(System.currentTimeMillis() - 2 * 3600_000L) }
        val fresh = File(library.parentFile, "y.tmp").apply { writeText("being written") }
        load(assets(), storage)
        assertTrue(libraryBytes.contentEquals(library.readBytes()))
        assertEquals(library.absolutePath, nativeCalls.single().second)
        assertFalse(old.exists())
        assertFalse(abandoned.exists())
        assertTrue(fresh.exists()) // may belong to another process still copying
    }

    // --- Build ----------------------------------------------------------------------------------------------

    @Test fun theReleaseIsANewUnsignedPackageWithoutNativeLibrariesInLib() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("applicationId = \"io.github.tama_no_holiday.anmitsu\""))
        assertTrue(gradle.lines().any { it.trim() == "versionCode = 1" })
        assertTrue(gradle.contains("versionName = \"1.0.0\""))
        assertFalse(gradle.contains("jniLibs"))
        assertFalse(gradle.contains("signingConfig ="))
    }
}

/**
 * 1.0.0: what a fresh install does when an editor starts, in whichever variant runs this (testDebugUnitTest or
 * testReleaseUnitTest): no preference is written, so only the defaults turn the features on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ReleaseDefaultsTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun idleUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test fun aFreshInstallStartsTheFullDictionaryZenzAndCorrection() {
        val controller = Robolectric.buildService(TestIme::class.java).create()
        try {
            val ime = controller.get()
            val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
            ime.ic = FakeEditor(View(ime), "", 0, 0)
            ime.info = info
            ime.onStartInput(info, false)
            val worker = ime.conversionWorker
            // Both are asked for as the editor starts, before anything has been loaded.
            assertEquals(OssState.LOADING, worker.ossState)
            assertEquals(OssState.LOADING, worker.zenzState)
            assertTrue(worker.correctionEnabled)

            idleUntil("the dictionary and zenz") { worker.ossState != OssState.LOADING && worker.zenzState != OssState.LOADING }
            // Unit-test assets may carry only notices (MISSING); never a failed or skipped load.
            assertTrue(worker.ossState.toString(), worker.ossState in setOf(OssState.READY, OssState.MISSING))
            // The host JVM is no arm64 phone: zenz stops at the bundle or the CPU check and copies nothing.
            assertTrue(worker.zenzState.toString(), worker.zenzState in setOf(OssState.MISSING, OssState.UNSUPPORTED))
            assertTrue(File(app.noBackupFilesDir, "zenz").walkTopDown().none { it.isFile })
        } finally {
            controller.destroy()
        }
    }
}
