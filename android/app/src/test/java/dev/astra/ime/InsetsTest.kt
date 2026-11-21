package dev.astra.ime

import android.graphics.Insets
import android.graphics.Rect
import android.text.InputType
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real input root and KeyboardView at mdpi (1dp = 1px): min bottom 48px, portrait rows 56px. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class InsetsTest {
    @Before fun useSmallFixtureForLayoutTests() {
        val context = RuntimeEnvironment.getApplication()
        DebugSettings.setOssEnabled(context, false)
        DebugSettings.setZenzEnabled(context, false)
    }

    private var controller: ServiceController<TestIme>? = null

    @After fun tearDown() { controller?.destroy() }

    private fun root(): ViewGroup {
        controller = Robolectric.buildService(TestIme::class.java).create()
        return controller!!.get().onCreateInputView() as ViewGroup
    }

    private fun startEditor(): FakeEditor {
        val ime = controller!!.get()
        val editor = FakeEditor(View(ime), "", 0, 0)
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0 }
        ime.ic = editor
        ime.info = info
        ime.onStartInput(info, false)
        return editor
    }

    private fun layout(root: View) {
        root.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun apply(root: View, insets: WindowInsets): IntArray {
        root.dispatchApplyWindowInsets(insets)
        layout(root)
        return intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom, root.height)
    }

    /** API 26..29 shape: only system-window insets exist (hidden constructor, present in SDK 28). */
    private fun legacy(left: Int, right: Int, bottom: Int): WindowInsets =
        WindowInsets::class.java.getConstructor(Rect::class.java).newInstance(Rect(left, 0, right, bottom))

    private fun modern(nav: Insets, gestures: Insets = Insets.NONE, ime: Int = 0): WindowInsets = WindowInsets.Builder()
        .setInsets(WindowInsets.Type.navigationBars(), nav)
        .setInsets(WindowInsets.Type.mandatorySystemGestures(), gestures)
        .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, ime))
        .build()

    private fun touch(root: View, time: Long, action: Int, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(0L, time, action, x, y, 0)
        return root.dispatchTouchEvent(event).also { event.recycle() }
    }

    @Config(sdk = [28])
    @Test fun legacyInsetsPadRootAbsolutelyAndKeysStayAboveSafeArea() {
        val root = root()
        val kb = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
        val first = apply(root, legacy(0, 0, 48))
        assertEquals(listOf(0, 0, 0, 48), first.take(4))
        assertEquals(first.toList(), apply(root, legacy(0, 0, 48)).toList()) // no accumulation
        assertEquals(4 * 56, kb.height)
        assertEquals(root.height - 48, kb.bottom)
        assertEquals(48, apply(root, legacy(0, 0, 0))[3]) // zero/consumed: fallback
        assertEquals(48, apply(root, legacy(0, 0, 20))[3]) // max, not sum
        assertEquals(listOf(0, 0, 96, 48), apply(root, legacy(0, 96, 0)).take(4)) // landscape side bar
        assertEquals(1080 - 96, kb.right)
        assertEquals(listOf(0, 0, 0, 48), apply(root, legacy(0, 0, 48)).take(4)) // rotated back
        assertEquals(first.toList(), apply(root, legacy(0, 0, 48)).toList())
    }

    @Config(sdk = [30])
    @Test fun modernInsetsUseNavAndGestureMaxButNeverIme() {
        val root = root()
        val kb = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
        val first = apply(root, modern(Insets.of(0, 0, 0, 48), Insets.of(60, 0, 60, 56), ime = 800))
        assertEquals(listOf(0, 0, 0, 56), first.take(4)) // side back-gesture zones are not padded
        assertEquals(root.height - 56, kb.bottom)
        assertEquals(first.toList(), apply(root, modern(Insets.of(0, 0, 0, 48), Insets.of(60, 0, 60, 56), ime = 800)).toList())
        assertEquals(listOf(0, 0, 0, 48), apply(root, modern(Insets.NONE, ime = 800)).take(4))
        assertEquals(listOf(126, 0, 0, 48), apply(root, modern(Insets.of(126, 0, 0, 0))).take(4))
        assertEquals(126, kb.left)
    }

    @Config(sdk = [28])
    @Test fun bottomRowStillTypesAndSafeAreaTouchEmitsNothing() {
        val root = root()
        val editor = startEditor()
        apply(root, legacy(0, 0, 48))
        val kb = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
        // Where the OS hide chevron and IME switcher sit: plain background, nothing handles it.
        for (x in floatArrayOf(60f, 1020f)) {
            assertFalse(touch(root, 0, ACTION_DOWN, x, root.height - 24f))
            touch(root, 10, ACTION_UP, x, root.height - 24f)
        }
        assertEquals("", editor.text.toString())
        // Kana bottom row weights: ABC 1 | 小 1.25 | わ 1.25 | 、。 1.25 | Enter 1 (total 5.75).
        val rowY = kb.top + kb.height * 3.5f / 4
        val waX = kb.left + kb.width * 2.875f / 5.75f
        val enterX = kb.left + kb.width * 5.25f / 5.75f
        assertTrue(touch(root, 20, ACTION_DOWN, waX, rowY))
        touch(root, 30, ACTION_MOVE, waX, rowY - 40f)
        touch(root, 40, ACTION_UP, waX, rowY - 40f)
        touch(root, 50, ACTION_DOWN, waX, rowY)
        touch(root, 60, ACTION_UP, waX, rowY)
        assertEquals("んわ", editor.text.toString())
        assertEquals(0, editor.composingStart)
        // A key press that drifts into the safe area still belongs to the key that got the down.
        touch(root, 70, ACTION_DOWN, enterX, rowY)
        touch(root, 80, ACTION_MOVE, enterX, rowY + 40f)
        touch(root, 90, ACTION_UP, enterX, rowY + 40f)
        assertEquals("んわ", editor.text.toString())
        assertEquals(-1, editor.composingStart) // Enter confirmed the raw composition
        assertEquals(0, editor.reads)
    }

    @Config(sdk = [28])
    @Test fun keyboardHonorsParentHeightConstraints() {
        val kb = KeyboardView(RuntimeEnvironment.getApplication())
        val rows = Keyboards.kana("⏎")
        kb.setRows(rows)
        val width = MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY)
        kb.measure(width, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        assertEquals(4 * 56, kb.measuredHeight)
        kb.measure(width, MeasureSpec.makeMeasureSpec(300, MeasureSpec.EXACTLY))
        assertEquals(300, kb.measuredHeight)
        kb.measure(width, MeasureSpec.makeMeasureSpec(100, MeasureSpec.AT_MOST))
        assertEquals(100, kb.measuredHeight)
        kb.layout(0, 0, 400, 100)
        for (r in 1 until rows.size) assertTrue(rows[r][0].rect.top >= rows[r - 1][0].rect.bottom)
        assertTrue(rows.flatten().all { it.rect.bottom <= 100f && it.rect.right <= 400.01f })
    }
}
