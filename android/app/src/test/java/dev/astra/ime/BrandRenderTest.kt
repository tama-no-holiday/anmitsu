package dev.astra.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.View
import android.view.View.MeasureSpec
import android.widget.ScrollView
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Writes PNGs for a person to look at; asserts nothing and is skipped unless ANMITSU_RENDER_DIR names a directory.
 * Robolectric's host renderer, not a device: text may lack Japanese glyphs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandRenderTest {
    private val out = System.getenv("ANMITSU_RENDER_DIR")?.let(::File)

    private fun save(name: String, width: Int, height: Int, draw: (Canvas) -> Unit) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun saveView(name: String, view: View, width: Int) {
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        save(name, view.measuredWidth, view.measuredHeight) { view.draw(it) }
    }

    @Test fun render() {
        assumeTrue(out != null)
        out!!.mkdirs()
        val app = RuntimeEnvironment.getApplication()
        val icon = app.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        save("icon-full-bleed", 648, 648) { canvas ->
            for (layer in listOf(icon.background, icon.foreground)) {
                layer.setBounds(0, 0, 648, 648)
                layer.draw(canvas)
            }
        }
        icon.setBounds(0, 0, 432, 432)
        save("icon-masked", 432, 432) { icon.draw(it) }
        val mono = app.getDrawable(R.drawable.ic_launcher_monochrome)!!
        save("icon-monochrome", 432, 432) { canvas ->
            canvas.drawColor(0xFF3B1A22.toInt())
            mono.setBounds(0, 0, 432, 432)
            mono.draw(canvas)
        }
        for (theme in KeyboardTheme.entries) {
            ThemeSettings.setTheme(app, theme)
            saveView("keys-${theme.id}", KeyboardView(app).apply {
                colors = theme.colors
                setRows(Keyboards.kana("⏎"))
            }, 1233)
            val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
            val page = (activity.window.decorView as android.view.ViewGroup).let { root ->
                fun find(v: View): ScrollView? = v as? ScrollView ?: (v as? android.view.ViewGroup)?.let { g ->
                    (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) }
                }
                find(root)!!.getChildAt(0)
            }
            saveView("setup-${theme.id}", page, 1233)
        }
    }
}
