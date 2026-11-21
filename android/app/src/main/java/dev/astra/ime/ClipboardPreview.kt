package dev.astra.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.icu.text.BreakIterator

/** The clipboard operations the keyboard uses; JVM tests substitute it, so no test touches a real clipboard. */
internal interface ClipSource {
    /** Only the metadata: asking for it does not hand over the clip's text. */
    fun description(): ClipDescription?
    fun clip(): ClipData?
    fun listen(listener: ClipboardManager.OnPrimaryClipChangedListener)
    fun unlisten(listener: ClipboardManager.OnPrimaryClipChangedListener)
}

internal class SystemClipSource(context: Context) : ClipSource {
    private val manager = context.getSystemService(ClipboardManager::class.java)
    override fun description(): ClipDescription? = manager?.primaryClipDescription
    override fun clip(): ClipData? = manager?.primaryClip
    override fun listen(listener: ClipboardManager.OnPrimaryClipChangedListener) { manager?.addPrimaryClipChangedListener(listener) }
    override fun unlisten(listener: ClipboardManager.OnPrimaryClipChangedListener) { manager?.removePrimaryClipChangedListener(listener) }
}

/**
 * What may be read from a clip, and the bounded one-line preview. Text is only ever taken from
 * ClipData.Item.getText of item 0: never coerceToText, getHtmlText, a URI, an Intent or a content provider.
 * https://developer.android.com/develop/ui/views/touch-and-input/copy-paste
 * https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling
 */
internal object ClipPolicy {
    /** ClipDescription.EXTRA_IS_SENSITIVE (API 33); the literal also works for clips marked on older versions. */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    const val PREVIEW_CODE_POINTS = 80

    /** Enough to find a grapheme boundary before the cut (a ZWJ family is 7 code points, a flag tag 14 at most). */
    private const val LOOKAHEAD = 32

    /** A preview never scans further than this, so a huge clip of blanks costs no more than a short one. */
    private const val MAX_SCANNED_CHARS = 4096
    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D

    /** Text with no sensitive mark. A value of another type counts as marked. */
    fun readable(description: ClipDescription): Boolean {
        if (!description.hasMimeType("text/*")) return false
        val extras = description.extras ?: return true
        return !(extras.containsKey(EXTRA_IS_SENSITIVE) && extras.getBoolean(EXTRA_IS_SENSITIVE, true))
    }

    /** Item 0's plain text, or null for anything else. The mark is checked again: the clip may have just changed. */
    fun plainText(clip: ClipData): CharSequence? {
        if (!readable(clip.description) || clip.itemCount < 1) return null
        val item = clip.getItemAt(0)
        if (item.uri != null || item.intent != null) return null
        return item.text?.takeIf { it.isNotEmpty() }
    }

    /**
     * One line of at most [PREVIEW_CODE_POINTS] code points plus "…", without spans, or null when nothing is visible.
     * Line breaks and other blanks collapse to one space; control and bidirectional format characters are dropped
     * (ZWJ/ZWNJ stay, emoji sequences need them). The cut never splits a user-perceived character.
     */
    fun preview(text: CharSequence): String? {
        val out = StringBuilder()
        var count = 0
        var blank = false
        var i = 0
        val limit = minOf(text.length, MAX_SCANNED_CHARS)
        while (i < limit && count < PREVIEW_CODE_POINTS + LOOKAHEAD) {
            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            when {
                isBlank(cp) -> blank = out.isNotEmpty()
                isDropped(cp) -> Unit
                else -> {
                    if (blank) {
                        out.append(' ')
                        count++
                        blank = false
                    }
                    out.appendCodePoint(cp)
                    count++
                }
            }
        }
        if (out.isEmpty()) return null
        if (count <= PREVIEW_CODE_POINTS) return if (i < text.length) "$out…" else out.toString()
        val cut = out.offsetByCodePoints(0, PREVIEW_CODE_POINTS)
        val graphemes = BreakIterator.getCharacterInstance().apply { setText(out.toString()) }
        val boundary = if (graphemes.isBoundary(cut)) cut else graphemes.preceding(cut)
        return out.substring(0, if (boundary > 0) boundary else cut).trimEnd() + "…"
    }

    private fun isBlank(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.getType(cp) == Character.CONTROL.toInt()

    private fun isDropped(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.FORMAT -> cp != ZWJ && cp != ZWNJ
        Character.SURROGATE -> true // unpaired
        else -> false
    }
}

/** One clip as read for a paste: dropped right after it. Only [stamp] may outlive it, in [ConsumedClip]. */
internal class ClipSnapshot(val text: CharSequence, val stamp: Long)

/**
 * Which clip a paste from the preview used up, so that clip stays hidden across keyboard hide/show and editor changes.
 * Process memory only, on the main thread: a copy time, never the text, a hash of it or any history. The system
 * clipboard itself is never changed.
 *
 * Identity is ClipDescription.getTimestamp (API 26, set by the system on every copy), so a new copy of identical text
 * shows again while a late or repeated report of the used clip does not. Two copies within one millisecond would look
 * like one. A clip without a timestamp (0) has no identity: it stays hidden only until the next report or the next
 * time the keyboard hides. A used clip showing again is harmless; hiding a new clip would not be.
 */
internal class ConsumedClip {
    private var stamp = 0L
    private var unstamped = false

    fun matches(timestamp: Long): Boolean = if (timestamp > 0) timestamp == stamp else unstamped

    fun mark(timestamp: Long) {
        if (timestamp > 0) stamp = timestamp else unstamped = true
    }

    /** No report can be matched to a clip without a timestamp, and copies made while hidden are not reported at all. */
    fun releaseUnstamped() {
        unstamped = false
    }

    /** For tests: the process outlives each of them. */
    fun forget() {
        stamp = 0L
        unstamped = false
    }

    companion object {
        /** Survives the service: switching keyboards recreates it while the process lives on. */
        val process = ConsumedClip()
    }
}

/**
 * Clipboard preview of the visible keyboard. Holds only the bounded [preview], never the clip or its full text;
 * nothing is logged, stored, learned or sent. The clipboard is read when the keyboard starts on an editor and when
 * the system reports a new clip, not per keystroke. Any failure to read means "no usable clip". A clip used by a paste
 * ([markPasted]) is not read again: only its metadata is, to recognize it.
 */
internal class ClipboardPreview(
    private val source: ClipSource,
    private val consumed: ConsumedClip,
    private val onChanged: () -> Unit,
) {
    var preview: String? = null
        private set

    /** Listening: the keyboard is visible on an editor that allows the preview. */
    var active = false
        private set

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (active) {
            consumed.releaseUnstamped()
            refresh()
        }
    }

    fun show() {
        if (!active) {
            active = true
            guarded { source.listen(listener) }
        }
        refresh()
    }

    /** Stops listening and forgets the preview (keyboard hidden, editor changed). */
    fun hide() {
        if (active) {
            active = false
            guarded { source.unlisten(listener) }
        }
        consumed.releaseUnstamped()
        publish(null)
    }

    fun refresh() = publish(if (active) current()?.let { ClipPolicy.preview(it.text) } else null)

    /**
     * The usable clip as it is now, read anew each time so a paste can never use an old snapshot. The metadata comes
     * first: a clip marked sensitive or already pasted is not even fetched. Identity comes from the fetched clip
     * itself, which may be newer than the metadata.
     */
    fun current(): ClipSnapshot? {
        if (!active) return null
        return guarded {
            val description = source.description()?.takeIf(ClipPolicy::readable) ?: return@guarded null
            if (consumed.matches(description.timestamp)) return@guarded null
            val clip = source.clip() ?: return@guarded null
            val text = ClipPolicy.plainText(clip) ?: return@guarded null
            ClipSnapshot(text, clip.description.timestamp).takeUnless { consumed.matches(it.stamp) }
        }
    }

    /** The editor accepted [clip]: the preview goes away and the clipboard stays exactly as it is. */
    fun markPasted(clip: ClipSnapshot) {
        consumed.mark(clip.stamp)
        refresh()
    }

    private fun publish(next: String?) {
        if (next == preview) return
        preview = next
        onChanged()
    }

    /** Denied access (not the default IME, no focus), a dead clipboard service, a malformed clip. */
    private inline fun <T> guarded(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    }
}
