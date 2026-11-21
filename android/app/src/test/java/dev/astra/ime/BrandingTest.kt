package dev.astra.ime

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.RadioButton
import android.widget.TextView
import java.io.File
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 0.1.25: the あんみつ name, mark, two themes and guide words; nothing a user already chose may change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BrandingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun screen() = views(Robolectric.buildActivity(SetupActivity::class.java).setup().get().window.decorView)
    private fun storedTheme() = app.getSharedPreferences("astra_theme", Context.MODE_PRIVATE)

    // --- Icon ----------------------------------------------------------------------------------------

    private fun res(path: String) = File("src/main/res/$path").readText()

    @Test fun theLauncherIconIsTheAdaptiveMarkWithRoundAndThemedForms() {
        assertEquals(R.mipmap.ic_launcher, app.applicationInfo.icon)
        assertTrue(app.getDrawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
        assertTrue(app.getDrawable(R.mipmap.ic_launcher_round) is AdaptiveIconDrawable)
        // 0.1.27: the user's icon (design/icon/anmitsu-icon): あ, three underlines and a caret on cream.
        assertEquals(0xFFF4ECDD.toInt(), app.getColor(R.color.ic_launcher_background))
        assertTrue("roundIcon" in File("src/main/AndroidManifest.xml").readText())
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            assertTrue(name, "@drawable/ic_launcher_monochrome" in res("mipmap-anydpi/$name.xml"))
        }
        val foreground = res("drawable/ic_launcher_foreground.xml")
        for (color in listOf("#2B1A17", "#E59A9A", "#C8923A", "#8DB07E", "#7A2E35")) assertTrue(color, color in foreground)
        // The themed layer is one color (the launcher tints it) with the same shapes.
        val mono = res("drawable/ic_launcher_monochrome.xml")
        assertEquals(setOf("FF000000"), Regex("fillColor=\"#([0-9A-F]+)\"").findAll(mono).map { it.groupValues[1] }.toSet())
        fun paths(xml: String) = Regex("pathData=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(paths(foreground), paths(mono))
        // Shipped as the user gave it.
        assertEquals(File("../../design/icon/anmitsu-icon/res/drawable/ic_launcher_foreground.xml").readText().replace("\r", ""), foreground.replace("\r", ""))
    }

    @Test fun theMarkStaysInsideTheSafeZone() {
        val xml = res("drawable/ic_launcher_foreground.xml")
        assertFalse("no group transforms", "<group" in xml)
        val paths = Regex("pathData=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        // The あ outline uses absolute commands only: every number pair is a point on or a control point of its curve
        // (a Bézier stays inside its control points, so these bound it), plus half its 0.72 stroke.
        assertTrue(paths[0].none { it in "mlhvcsqtaz" })
        val glyph = Regex("-?[0-9.]+").findAll(paths[0]).map { it.value.toDouble() }.chunked(2).map { (x, y) -> hypot(x - 54, y - 54) + 0.36 }
        // Underlines and caret: rounded bars of radius 2, given by their end circles' centers.
        val caps = listOf(36.8 to 71.2, 63.2 to 71.2, 73.2 to 38.4, 73.2 to 71.2).map { (x, y) -> hypot(x - 54, y - 54) + 2 }
        val farthest = (glyph + caps).max()
        assertTrue("reaches $farthest dp from the center", farthest <= 33.0)
        assertTrue("fills too little: $farthest", farthest >= 24.0)
    }

    // --- Themes --------------------------------------------------------------------------------------

    @Test fun aStoredChoiceSurvivesTheUpdateAndNothingIsRewritten() {
        for (id in listOf("white", "gray", "dark")) {
            storedTheme().edit().clear().putString("theme", id).commit()
            assertEquals(id, ThemeSettings.theme(app).id)
            val radios = screen().filterIsInstance<RadioButton>()
            assertEquals(id, radios.single { it.isChecked }.let { (it.tag as KeyboardTheme).id })
            assertEquals(mapOf("theme" to id), storedTheme().all)
        }
    }

    @Test fun nothingStoredStaysWhiteAndOnlyAChoiceStoresANewTheme() {
        assertEquals(KeyboardTheme.WHITE, ThemeSettings.theme(app))
        val radios = screen().filterIsInstance<RadioButton>()
        assertEquals(KeyboardTheme.WHITE, radios.single { it.isChecked }.tag)
        assertTrue(storedTheme().all.isEmpty())
        radios.single { it.tag == KeyboardTheme.AZUKI }.performClick()
        assertEquals(mapOf("theme" to "azuki"), storedTheme().all)
        radios.single { it.tag == KeyboardTheme.ANMITSU }.performClick()
        assertEquals(mapOf("theme" to "anmitsu"), storedTheme().all)
    }

    @Test fun theEarlierThemesKeepEveryColor() {
        fun all(c: KeyboardColors) = listOf(c.background, c.key, c.functionKey, c.pressed, c.shadow, c.highlight, c.accent, c.onAccent, c.text,
            c.hint, c.primary, c.shown, c.onShown, c.repairRow, c.tabText, c.scrollbar, c.ripple)
        assertEquals(listOf(0xFFB8BCC2, 0xFFE1E3E6, 0xFFC9CCD1, 0xFFA3A8AE, 0xFF8F949A, 0xFFA9CBEF, 0xFF0D47A1, 0xFFFFFFFF, 0xFF17181A,
            0xFF44494E, 0xFF0A2F6E, 0xFFC5D6F0, 0xFF0A2F6E, 0xFFC9CCD1, 0xFF303438, 0x88000000, 0x33000000).map { it.toInt() }, all(KeyboardTheme.GRAY.colors))
        assertEquals(listOf(0xFF1B1C1F, 0xFF3C4043, 0xFF2A2C30, 0xFF5F6368, 0xFF0E0F10, 0xFF1F4E8C, 0xFF8AB4F8, 0xFF0B1E3A, 0xFFE8EAED,
            0xFFBDC1C6, 0xFF8AB4F8, 0xFF1F4E8C, 0xFFE8EAED, 0xFF2A2C30, 0xFFBDC1C6, 0x88FFFFFF, 0x33FFFFFF).map { it.toInt() }, all(KeyboardTheme.DARK.colors))
    }

    @Test fun theNewThemesAreReadableWhereTheGeneralCheckDoesNotLook() {
        for (theme in listOf(KeyboardTheme.AZUKI, KeyboardTheme.ANMITSU)) {
            val c = theme.colors
            fun readable(what: String, foreground: Int, background: Int, minimum: Double = 4.5) {
                val ratio = contrast(foreground, background)
                assertTrue("$theme $what: $ratio", ratio >= minimum)
            }
            // A held key keeps its hints; a highlighted key too (KeyboardView); the panel marks a pressed candidate.
            readable("hint on pressed", c.hint, c.pressed)
            readable("hint on highlight", c.hint, c.highlight)
            readable("primary on pressed", c.primary, c.pressed)
            readable("hint on repair row", c.hint, c.repairRow)
            readable("tab on function color", c.tabText, c.repairRow)
            // Non-text: selection, divider and preview must stand out from the strip.
            readable("selected chip", c.shown, c.background, 1.2)
            readable("divider", c.hint, c.background, 3.0)
            readable("preview bubble", c.accent, c.background, 3.0)
            readable("key edge", c.shadow, c.background, 1.2)
        }
    }

    @Test fun theSetupSwatchesTellEveryThemeApart() {
        val swatches = KeyboardTheme.entries.map { Triple(it.colors.background, it.colors.key, it.colors.primary) }
        assertEquals(swatches.size, swatches.toSet().size)
    }

    // --- Guide ---------------------------------------------------------------------------------------

    private fun strips(all: List<View>) = all.filter { it.tag == TAG_GUIDE_PICTURE }.filterIsInstance<HorizontalScrollView>()
    private fun chips(strip: HorizontalScrollView) = views(strip).filterIsInstance<TextView>().filter { it.text.isNotEmpty() }

    @Test fun theGuidePicturesShowSweetsLaidOutAs0_1_24DrawsThem() {
        val (convert, segments, symbols) = strips(screen()).map { chips(it).map { c -> c.text.toString() } }
        assertEquals(listOf("白玉", "あんみつ", "白玉あんみつ", "白玉餡蜜", "しらたまあんみつ"), convert)
        assertEquals(listOf("抹茶", "あんみつ", "あんみつ", "餡蜜", "アンミツ"), segments)
        assertEquals("括弧", symbols.first()) // the real strip: buttons first, then the shown candidate
        // Buttons add up to the shown candidate, and the kana is what they read.
        assertEquals("しらたまあんみつ", GuideExamples.reading(GuideExamples.CONVERT))
        assertEquals("白玉あんみつ", GuideExamples.surface(GuideExamples.CONVERT))
        assertEquals("まっちゃあんみつ", GuideExamples.reading(GuideExamples.SELECTED))
        val strings = R.string::class.java.fields.map { app.getString(it.getInt(null)) } +
            R.array::class.java.fields.flatMap { app.resources.getStringArray(it.getInt(null)).toList() }
        val shown = screen().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        for (old in listOf("入力時", "にゅうりょく", "Astra", "Codex", "青い")) {
            assertFalse(old, old in shown)
            assertTrue(old, strings.none { old in it })
        }
    }

    @Test fun theEnglishExampleIsInTheShippedIndex() {
        assertTrue(app.getString(R.string.setup_section_guide).isNotEmpty())
        val line = File("src/main/assets/english/english-words.tsv").useLines { lines -> lines.first { it.startsWith("azuki\t") } }
        assertTrue(line, "AZUKI" in line.split('\t'))
        assertTrue(app.resources.getStringArray(R.array.guide_english_items).any { "azuki → AZUKI" in it })
    }

    @Test fun pickingAThemeRepaintsTheGuidePicturesAtOnce() {
        val all = screen()
        val c = KeyboardTheme.AZUKI.colors
        all.filterIsInstance<RadioButton>().single { it.tag == KeyboardTheme.AZUKI }.performClick()
        assertTrue(all.filter { it.tag == TAG_GUIDE_PICTURE }.filterIsInstance<KeyboardView>().all { it.colors == c })
        val selected = chips(strips(all)[1]).first { it.text == "あんみつ" }
        assertEquals(c.onShown, selected.currentTextColor)
        assertEquals(c.primary, chips(strips(all)[1]).first { it.text == "抹茶" }.currentTextColor)
        assertEquals(c.text, chips(strips(all)[1]).first { it.text == "餡蜜" }.currentTextColor)
    }
}
