package dev.astra.ime

import android.text.InputType as T
import android.view.inputmethod.EditorInfo as E
import org.junit.Assert.*
import org.junit.Test

/** Only field-based policy tests; does not simulate Android editor/IME lifecycle. */
class EditorPolicyTest {
    private fun editor(type: Int, options: Int = 0) = E().also {
        it.inputType = type
        it.imeOptions = options
    }

    @Test fun committedContextIsOnlyForPlainTextFieldsThatAllowHistory() {
        assertTrue(EditorPolicy.allowsCommittedContext(editor(T.TYPE_CLASS_TEXT)))
        assertTrue(EditorPolicy.allowsCommittedContext(editor(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE)))
        assertFalse(EditorPolicy.allowsCommittedContext(editor(T.TYPE_CLASS_TEXT, E.IME_FLAG_NO_PERSONALIZED_LEARNING)))
        for (type in listOf(
            T.TYPE_NULL, T.TYPE_CLASS_NUMBER, T.TYPE_CLASS_PHONE, T.TYPE_CLASS_DATETIME,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_PASSWORD, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )) assertFalse("type $type", EditorPolicy.allowsCommittedContext(editor(type)))
    }

    @Test fun allPasswordVariationsBypassConversion() {
        for (variation in listOf(T.TYPE_TEXT_VARIATION_PASSWORD,
            T.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, T.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            for (options in listOf(0, E.IME_FLAG_NO_PERSONALIZED_LEARNING)) {
                val info = editor(T.TYPE_CLASS_TEXT or variation or T.TYPE_TEXT_FLAG_MULTI_LINE, options)
                assertTrue(EditorPolicy.isPassword(info.inputType))
                assertTrue(EditorPolicy.isDirectInput(info))
                assertFalse(EditorPolicy.allowsLearning(info))
                assertFalse(EditorPolicy.allowsTypoCorrection(info))
                assertFalse(EditorPolicy.allowsLiveConversion(info))
                assertEquals(KeyboardMode.ASCII, EditorPolicy.initialMode(info))
            }
        }
        val numeric = editor(T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_VARIATION_PASSWORD)
        assertTrue(EditorPolicy.isPassword(numeric.inputType))
        assertTrue(EditorPolicy.isDirectInput(numeric))
        assertFalse(EditorPolicy.allowsLearning(numeric))
        assertFalse(EditorPolicy.allowsLiveConversion(numeric))
        assertEquals(KeyboardMode.SYMBOLS, EditorPolicy.initialMode(numeric))
        val raw = editor(T.TYPE_NULL)
        assertTrue(EditorPolicy.isDirectInput(raw))
        assertFalse(EditorPolicy.allowsLearning(raw))
        assertFalse(EditorPolicy.allowsTypoCorrection(raw))
        assertFalse(EditorPolicy.allowsLiveConversion(raw))
    }

    @Test fun variationBitsAreInterpretedWithinTheirClass() {
        // URI and numeric password share variation bits; a URL is not a password field.
        val uri = editor(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI)
        assertFalse(EditorPolicy.isPassword(uri.inputType))
        assertFalse(EditorPolicy.isDirectInput(uri))
        assertEquals(KeyboardMode.KANA, EditorPolicy.initialMode(uri))
        assertFalse(EditorPolicy.isPassword(T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_FLAG_DECIMAL))
        // Same bit as NO_SUGGESTIONS outside the text class must not make a number field private.
        val number = editor(T.TYPE_CLASS_NUMBER or T.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertTrue(EditorPolicy.allowsLearning(number))
    }

    /** Address-bar-like fields (source diagnosis; Chrome's actual flags were not measured). */
    @Test fun uriAndNoSuggestionFieldsConvertLiveButNeverLearnOrCorrectTypos() {
        val uri = T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI
        for (type in listOf(uri, uri or T.TYPE_TEXT_FLAG_NO_SUGGESTIONS, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_NO_SUGGESTIONS)) {
            for (options in listOf(E.IME_ACTION_GO, E.IME_ACTION_SEARCH or E.IME_FLAG_NO_PERSONALIZED_LEARNING)) {
                val info = editor(type, options)
                assertFalse(EditorPolicy.isDirectInput(info))
                assertFalse(EditorPolicy.allowsLearning(info))
                assertFalse(EditorPolicy.allowsTypoCorrection(info))
                assertTrue(EditorPolicy.allowsLiveConversion(info))
                assertTrue(EditorPolicy.editorAction(info) != null)
                assertEquals(KeyboardMode.KANA, EditorPolicy.initialMode(info))
            }
        }
    }

    @Test fun incognitoTextConvertsLiveButNeverLearns() {
        val info = editor(T.TYPE_CLASS_TEXT, E.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertFalse(EditorPolicy.isDirectInput(info))
        assertFalse(EditorPolicy.allowsLearning(info))
        assertTrue(EditorPolicy.allowsTypoCorrection(info))
        assertTrue(EditorPolicy.allowsLiveConversion(info))
        assertEquals(KeyboardMode.KANA, EditorPolicy.initialMode(info))
    }

    @Test fun emailFieldsStartInAsciiAndNeverLearnOrCorrectTypos() {
        for (variation in listOf(T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, T.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) {
            val info = editor(T.TYPE_CLASS_TEXT or variation)
            assertFalse(EditorPolicy.isDirectInput(info))
            assertFalse(EditorPolicy.allowsLearning(info))
            assertFalse(EditorPolicy.allowsTypoCorrection(info))
            assertTrue(EditorPolicy.allowsLiveConversion(info))
            assertEquals(KeyboardMode.ASCII, EditorPolicy.initialMode(info))
        }
    }

    @Test fun ordinaryTextFieldsConvertLiveAndLearn() {
        for (type in listOf(T.TYPE_CLASS_TEXT, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_SHORT_MESSAGE, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)) {
            val info = editor(type, E.IME_ACTION_SEARCH)
            assertFalse(EditorPolicy.isDirectInput(info))
            assertTrue(EditorPolicy.allowsLearning(info))
            assertTrue(EditorPolicy.allowsTypoCorrection(info))
            assertTrue(EditorPolicy.allowsLiveConversion(info))
            assertEquals(KeyboardMode.KANA, EditorPolicy.initialMode(info))
        }
    }

    @Test fun editorActionsRespectExplicitNewlinePolicy() {
        assertNull(EditorPolicy.editorAction(null))
        assertNull(EditorPolicy.editorAction(editor(T.TYPE_CLASS_TEXT, E.IME_ACTION_NONE)))
        assertNull(EditorPolicy.editorAction(editor(T.TYPE_CLASS_TEXT, E.IME_ACTION_UNSPECIFIED)))
        assertNull(EditorPolicy.editorAction(editor(T.TYPE_CLASS_TEXT, E.IME_ACTION_SEND or E.IME_FLAG_NO_ENTER_ACTION)))
        assertEquals(E.IME_ACTION_SEARCH, EditorPolicy.editorAction(editor(T.TYPE_CLASS_TEXT, E.IME_ACTION_SEARCH)))
        assertEquals("送信", EditorPolicy.enterLabel(editor(T.TYPE_CLASS_TEXT, E.IME_ACTION_SEND)))
    }
}
