package dev.astra.ime

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 0.1.23: the app screen reads like a released app: sections with pictures, no trial wording, no dictionary switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SetupGuideTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()

    private fun screen(): List<View> {
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        return views(activity.window.decorView)
    }

    @Test fun noTrialOrExperimentWordingAnywhereTheUserCanSee() {
        val app = RuntimeEnvironment.getApplication()
        val words = listOf("試験", "実験", "トライアル", "trial", "Trial", "OSS")
        for (field in R.string::class.java.fields) {
            val text = app.getString(field.getInt(null))
            for (w in words) assertFalse("${field.name}: $text", w in text)
        }
        for (field in R.array::class.java.fields) {
            for (item in app.resources.getStringArray(field.getInt(null))) {
                for (w in words) assertFalse("${field.name}: $item", w in item)
            }
        }
        val shown = screen().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        for (w in words) assertFalse(w, w in shown)
        assertEquals("anmitsu", app.getString(R.string.app_name))
        assertEquals("anmitsu", app.getString(R.string.ime_name))
        assertEquals("anmitsu", app.getString(R.string.setup_title))
    }

    @Test fun theDictionaryHasNoSwitchAndAnOldOffChoiceIsIgnored() {
        val app = RuntimeEnvironment.getApplication()
        // An earlier version's stored "off" must not leave anyone on the small lexicon without a way back.
        app.getSharedPreferences("astra_debug", 0).edit().putBoolean("oss_converter", false).commit()
        assertTrue(DebugSettings.ossEnabled(app))
        val boxes = screen().filterIsInstance<CheckBox>().map { it.text.toString() }
        assertTrue(boxes.none { "辞書" in it })
        assertTrue(boxes.contains(app.getString(R.string.setup_live_toggle)))
    }

    @Test fun everyGuideSectionIsThereWithKeyboardAndStripPictures() {
        val app = RuntimeEnvironment.getApplication()
        val all = screen()
        val texts = all.filterIsInstance<TextView>().map { it.text.toString() }
        for (title in listOf(R.string.setup_section_start, R.string.setup_section_guide, R.string.guide_kana_title, R.string.guide_convert_title,
            R.string.guide_segments_title, R.string.guide_symbols_title, R.string.guide_english_title, R.string.guide_more_title,
            R.string.setup_section_settings, R.string.setup_section_privacy, R.string.setup_section_licenses)) {
            assertTrue(app.getString(title), app.getString(title) in texts)
        }
        val pictures = all.filter { it.tag == TAG_GUIDE_PICTURE }
        val keyboards = pictures.filterIsInstance<KeyboardView>()
        assertEquals(2, keyboards.size) // かな and English, the real layouts
        assertTrue(keyboards[0].keyRows.flatten().any { it.flick != null })
        assertTrue(keyboards[1].keyRows.flatten().any { it.label == "abc" })
        assertEquals(3, pictures.filterIsInstance<HorizontalScrollView>().size) // strips: convert, segments, symbols
        // Pictures are not controls: a touch does nothing and they are hidden from accessibility.
        assertTrue(pictures.all { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS })
        assertTrue(keyboards.none { it.interactive })
        val down = android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        assertFalse(keyboards[0].onTouchEvent(down))
        down.recycle()
        // Every guide point is its own line, not one long paragraph.
        assertTrue(app.resources.getStringArray(R.array.guide_segments_items).all { it in texts })
        assertTrue(texts.any { it.startsWith(app.getString(R.string.setup_version, "").trim()) })
    }
}
