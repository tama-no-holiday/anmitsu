package dev.astra.ime

import android.os.Handler
import android.os.Looper
import dev.astra.ime.core.InputSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OssSwitchTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun idleUntil(condition: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
    }

    @Test
    fun switchesAreOnByDefaultAndCanBeTurnedOff() {
        // 1.0.0: no build-type gate, so this holds for testReleaseUnitTest too.
        assertTrue(DebugSettings.ossEnabled(app))
        assertTrue(DebugSettings.zenzEnabled(app))
        assertTrue(DebugSettings.correctionEnabled(app))
        assertTrue(DebugSettings.committedContext(app))
        assertTrue(DebugSettings.liveConversion(app))
        DebugSettings.setOssEnabled(app, false)
        assertFalse(DebugSettings.ossEnabled(app))
        DebugSettings.setLiveConversion(app, false)
        assertFalse(DebugSettings.liveConversion(app))
        DebugSettings.setOssEnabled(app, true)
        assertTrue(DebugSettings.ossEnabled(app))
    }

    @Test
    fun coreKeepsConvertingWhileOssIsAbsentAndOffReleasesIt() {
        val worker = ConversionWorker(app.assets, Handler(Looper.getMainLooper()))
        try {
            worker.load {}
            idleUntil { worker.isReady }
            assertTrue(worker.isReady)

            worker.setOssEnabled(true) {}
            idleUntil { worker.ossState != ConversionWorker.OssState.LOADING }
            // Unit-test assets carry only notices unless ASTRA_OSS_RESOURCES was set for the build.
            assertTrue(worker.ossState.toString(), worker.ossState in setOf(ConversionWorker.OssState.MISSING, ConversionWorker.OssState.READY))

            val session = InputSession()
            session.startInput(false)
            session.type("あめ")
            val token = session.request()!!
            var candidates: List<String>? = null
            worker.submit(token) { _, result, _ -> candidates = result.map { it.surface } }
            idleUntil { candidates != null }
            assertTrue(candidates!!.contains("あめ"))
            // Recorded in the test report so a bundled-data run is distinguishable from a notices-only one.
            println("ossState=${worker.ossState} ossLoadMillis=${worker.ossLoadMillis} candidates=${candidates!!.size}")
            if (worker.ossState == ConversionWorker.OssState.READY) {
                // Full Mozc data, verified against oss-assets.lock while loading.
                assertEquals("雨", candidates!!.first())
            }

            worker.setOssEnabled(false) {}
            assertEquals(ConversionWorker.OssState.OFF, worker.ossState)
        } finally {
            worker.shutdown()
        }
    }
}
