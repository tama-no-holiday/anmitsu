package dev.astra.ime.core

/** Flick gesture on a 12-key: a tap is [CENTER]. */
enum class FlickDirection { CENTER, LEFT, UP, RIGHT, DOWN }

/**
 * Logical kana keys of the standard Japanese 12-key layout. [label] is the key-top text.
 * Positions are the UI's choice; [FlickLayout.rows] gives the conventional arrangement.
 */
enum class FlickKey(val label: String) {
    A("あ"), KA("か"), SA("さ"),
    TA("た"), NA("な"), HA("は"),
    MA("ま"), YA("や"), RA("ら"),
    MODIFIER("小゛゜"), WA("わ"), PUNCT("、。？！"),
}

/**
 * Pure 12-key flick tables. Hiragana only; ASCII/digit keyboards are handled by the app.
 *
 * Direction order for each key is CENTER / LEFT / UP / RIGHT / DOWN:
 * - あ..ら rows: あ/い/う/え/お (vowel order).
 * - や: や / 「 / ゆ / 」 / よ (brackets as on iOS). Small ゃゅょ come from [toggle].
 * - わ: わ / を / ん / ー / (none).
 * - PUNCT: 、 / 。 / ？ / ！ / (none).
 * - MODIFIER produces no kana; on tap the app calls [toggle] on the last composed character
 *   (see [InputSession.toggleLast]).
 */
object FlickLayout {
    val rows: List<List<FlickKey>> = listOf(
        listOf(FlickKey.A, FlickKey.KA, FlickKey.SA),
        listOf(FlickKey.TA, FlickKey.NA, FlickKey.HA),
        listOf(FlickKey.MA, FlickKey.YA, FlickKey.RA),
        listOf(FlickKey.MODIFIER, FlickKey.WA, FlickKey.PUNCT),
    )

    private val table: Map<FlickKey, List<String?>> = mapOf(
        FlickKey.A to row("あいうえお"), FlickKey.KA to row("かきくけこ"), FlickKey.SA to row("さしすせそ"),
        FlickKey.TA to row("たちつてと"), FlickKey.NA to row("なにぬねの"), FlickKey.HA to row("はひふへほ"),
        FlickKey.MA to row("まみむめも"), FlickKey.YA to row("や「ゆ」よ"), FlickKey.RA to row("らりるれろ"),
        FlickKey.MODIFIER to row("_____"), FlickKey.WA to row("わをんー_"), FlickKey.PUNCT to row("、。？！_"),
    )

    // Each cycle maps a character to the next one; the last wraps to the first.
    private val cycles = listOf(
        "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ",
        "かが", "きぎ", "くぐ", "けげ", "こご",
        "さざ", "しじ", "すず", "せぜ", "そぞ",
        "ただ", "ちぢ", "つっづ", "てで", "とど",
        "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
        "やゃ", "ゆゅ", "よょ", "わゎ",
    )
    private val next: Map<Char, Char> = buildMap {
        for (c in cycles) c.forEachIndexed { i, ch -> put(ch, c[(i + 1) % c.length]) }
    }

    private fun row(chars: String): List<String?> = chars.map { if (it == '_') null else it.toString() }

    /** Character produced by flicking [key] in [direction], or null if that direction is blank. */
    fun kana(key: FlickKey, direction: FlickDirection): String? = table.getValue(key)[direction.ordinal]

    /** All non-blank outputs of [key], for drawing flick guides. */
    fun labels(key: FlickKey): Map<FlickDirection, String> =
        FlickDirection.entries.mapNotNull { d -> kana(key, d)?.let { d to it } }.toMap()

    /**
     * Next form for the 小゛゜ key: か→が→か, は→ば→ぱ→は, つ→っ→づ→つ, う→ぅ→ゔ→う,
     * あ→ぁ→あ, や→ゃ→や, わ→ゎ→わ. Returns null if [ch] has no alternate form.
     */
    fun toggle(ch: String): String? = if (ch.length == 1) next[ch[0]]?.toString() else null
}
