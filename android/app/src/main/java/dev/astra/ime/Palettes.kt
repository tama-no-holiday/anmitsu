package dev.astra.ime

/**
 * Symbol and emoji palettes: plain Unicode strings drawn with the system font (no image data, no network).
 * The lists are hand-written for this project (no third-party emoji data file is bundled). Each entry is one
 * user-perceived character and is committed whole, so multi-code-point sequences (variation selector, ZWJ,
 * keycap, flag) are never split. Entries the device font cannot draw are hidden at runtime ([renderable]).
 */
internal object Palettes {
    private const val VS = "️"
    private const val ZWJ = "‍"

    /** Leftmost tab of each palette while history is used: entries the user committed from it, most recent first. */
    const val HISTORY_LABEL = "履歴"

    /** [label] is the tab text: words only, no sample symbol or emoji in front of it. */
    class Category(val label: String, val items: List<String>)

    private fun list(vararg groups: String): List<String> = groups.flatMap { g -> g.split(' ').filter { it.isNotEmpty() } }

    /** Regional-indicator pair for an ISO 3166 code (or an exceptionally reserved one such as EU/UN). */
    private fun flag(code: String): String = code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    private fun keycap(base: Char): String = "$base$VS⃣"

    val symbols: List<Category> = listOf(
        Category("句読点", list(
            "、 。 ， ． ・ ： ； ？ ！ ゛ ゜ ー ― ‐ ～ 〜 … ‥ ／ ＼ ｜ ∥ ＿ ＾ ￣ ‘ ’ “ ” 〃 々 〆 〇 ヽ ヾ ゝ ゞ",
            "＠ ＃ ＆ ＊ ※ § ¶ † ‡ ¡ ¿ ´ ｀ ¨ 〓 〝 〟 & \"",
        )),
        Category("括弧", list(
            "「 」 『 』 （ ） 【 】 〔 〕 ［ ］ ｛ ｝ 〈 〉 《 》 〖 〗 〘 〙 ⦅ ⦆ ｢ ｣ « » ‹ › ( ) [ ] { } < >",
        )),
        Category("矢印", list(
            "→ ← ↑ ↓ ↔ ↕ ↗ ↘ ↙ ↖ ⇒ ⇐ ⇔ ⇑ ⇓ ⇄ ⇆ ⇅ ↻ ↺ ⇧ ⇩ ⇨ ⇦ ➔ ➜ ➝ ➞ ➤ ▸ ◂ ▹ ◃ ↳ ↲ ⇥ ⇤",
        )),
        Category("数学", list(
            "＋ － × ÷ ＝ ≠ ≒ ≈ ≡ ≦ ≧ ≤ ≥ ≪ ≫ ± ∓ ∞ ∴ ∵ √ ∛ ∫ ∬ ∮ ∑ ∏ ∂ ∇ ∀ ∃ ∈ ∋ ⊂ ⊃ ⊆ ⊇ ∪ ∩",
            "∧ ∨ ¬ ∠ ⊥ ⌒ ∝ ∽ ° ′ ″ ％ ‰ ½ ⅓ ⅔ ¼ ¾ ⅛ ⁰ ¹ ² ³ ⁴ ⁿ ₀ ₁ ₂ ₃ ∅ ∆ ⊕ ⊗ ∘ ∙ ⋯",
        )),
        Category("通貨・単位", list(
            "￥ ＄ ¢ £ € ₩ ₹ ₽ ฿ ₫ ₱ ₺ ₪ ¤ ℃ ℉ № ℡ ™ © ® ℓ Å ㎜ ㎝ ㎞ ㎎ ㎏ ㏄ ㎡ ㍉ ㌔ ㌢ ㍍ ㌘ ㌧ ㌃ ㌶ ㍑ ㍗ ㌍ ㌦ ㌣ ㌫ ㍊ ㌻",
        )),
        Category("図形", list(
            "○ ● ◎ ◯ ◇ ◆ □ ■ △ ▲ ▽ ▼ ☆ ★ ◐ ◑ ◒ ◓ ▣ ▤ ▥ ▦ ▧ ▨ ▩ ♤ ♠ ♧ ♣ ♡ ♥ ♢ ♦ ♪ ♫ ♬ ♩ ♭ ♯",
            "♀ ♂ 〒 〶 ✓ ✗ ✕ ☐ ☒ ✦ ✧ ❖ ✢ ✣ ✤ ✥ ❀ ✿ ❁ ☾ ☽ ⌘ ⏎ ⌫ ⌦",
        )),
        Category("囲み・番号", list(
            "① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩ ⑪ ⑫ ⑬ ⑭ ⑮ ⑯ ⑰ ⑱ ⑲ ⑳ ❶ ❷ ❸ ❹ ❺ ❻ ❼ ❽ ❾ ❿",
            "Ⅰ Ⅱ Ⅲ Ⅳ Ⅴ Ⅵ Ⅶ Ⅷ Ⅸ Ⅹ ⅰ ⅱ ⅲ ⅳ ⅴ ⅵ ⅶ ⅷ ⅸ ⅹ ㊤ ㊥ ㊦ ㊧ ㊨ ㈱ ㈲ ㈹ ㍾ ㍽ ㍼ ㍻ ㋿",
            "Ⓐ Ⓑ Ⓒ Ⓓ Ⓔ ⓐ ⓑ ⓒ ⓓ ⓔ",
        )),
        Category("ギリシャ・キリル", list(
            "α β γ δ ε ζ η θ ι κ λ μ ν ξ ο π ρ σ τ υ φ χ ψ ω Α Β Γ Δ Θ Λ Ξ Π Σ Φ Ψ Ω",
            "А Б В Г Д Е Ж З И К Л М Н О П Р С Т У Ф Х Ц Ч Ш Щ Ы Э Ю Я",
        )),
    )

    val emoji: List<Category> = listOf(
        Category("顔", list(
            "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 ☺$VS 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔",
            "🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐",
            "😕 😟 🙁 ☹$VS 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬",
            "😈 👿 💀 ☠$VS 💩 🤡 👹 👺 👻 👽 👾 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊",
        )),
        Category("人・手", list(
            "👋 🤚 🖐$VS ✋ 🖖 👌 🤏 ✌$VS 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝$VS 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏",
            "✍$VS 💅 🤳 💪 🦵 🦶 👂 👃 🧠 🦷 🦴 👀 👁$VS 👅 👄 💋 👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵",
            "🙍 🙎 🙅 🙆 💁 🙋 🙇 🤦 🤷 👮 🕵$VS 💂 👷 🤴 👸 👳 👲 🧕 🤵 👰 🤰 🤱 👼 🎅 🤶 🦸 🦹",
            "🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🏃 💃 🕺 👯 🧖 👪 👫 👬 👭 💑 💏 🗣$VS 👤 👥 👣",
        ) + listOf(
            "👨${ZWJ}⚕$VS", "👩${ZWJ}⚕$VS", "👨${ZWJ}🎓", "👩${ZWJ}🎓", "👨${ZWJ}🏫", "👩${ZWJ}🏫",
            "👨${ZWJ}🍳", "👩${ZWJ}🍳", "👨${ZWJ}💻", "👩${ZWJ}💻", "👨${ZWJ}🔧", "👩${ZWJ}🔬",
            "👨${ZWJ}🚀", "👩${ZWJ}🚒", "👨${ZWJ}👩${ZWJ}👧", "👨${ZWJ}👩${ZWJ}👧${ZWJ}👦",
        )),
        Category("動物", list(
            "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🐔 🐧 🐦 🐤 🐣 🐥 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄",
            "🐝 🐛 🦋 🐌 🐞 🐜 🦗 🕷$VS 🕸$VS 🦂 🦟 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦞 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈",
            "🐊 🐅 🐆 🦓 🦍 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🦚 🦜",
            "🦢 🕊$VS 🐇 🦝 🦡 🐁 🐀 🐿$VS 🦔 🐾 🐉 🐲",
        )),
        Category("自然", list(
            "💐 🌸 💮 🌹 🥀 🌺 🌻 🌼 🌷 🌱 🌲 🌳 🌴 🌵 🌾 🌿 ☘$VS 🍀 🍁 🍂 🍃 🍄 🌰 🌍 🌎 🌏",
            "🌑 🌒 🌓 🌔 🌕 🌖 🌗 🌘 🌙 🌚 🌛 🌜 🌝 🌞 ⭐ 🌟 🌠 ☀$VS 🌤$VS ⛅ 🌥$VS ☁$VS 🌦$VS 🌧$VS ⛈$VS",
            "🌩$VS 🌨$VS ❄$VS ☃$VS ⛄ 🌬$VS 💨 🌪$VS 🌫$VS 🌈 ☔ ☂$VS ⚡ 🔥 💧 🌊 🌋 🗻 🏔$VS ⛰$VS 🏕$VS 🏖$VS 🏜$VS 🏝$VS",
        )),
        Category("食べ物", list(
            "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶$VS 🌽 🥕 🥔 🍠",
            "🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🥞 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🥙 🌮 🌯 🥗 🥘 🥫 🍝 🍜 🍲 🍛",
            "🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🥜 🍯",
            "🥛 🍼 ☕ 🍵 🥤 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🍾 🥄 🍴 🍽$VS 🥣 🥡 🥢 🧂",
        )),
        Category("旅行・場所", list(
            "🚗 🚕 🚙 🚌 🚎 🏎$VS 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛴 🚲 🛵 🏍$VS 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞",
            "🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈$VS 🛫 🛬 🛩$VS 💺 🛰$VS 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥$VS 🛳$VS ⛴$VS 🚢 ⚓",
            "⛽ 🚧 🚦 🚥 🚏 🗺$VS 🗿 🗽 🗼 🏰 🏯 🏟$VS 🎡 🎢 🎠 ⛲ ⛱$VS 🏠 🏡 🏘$VS 🏚$VS 🏗$VS 🏭 🏢 🏬",
            "🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛$VS ⛪ 🕌 🕍 🕋 ⛩$VS 🗾 🏞$VS 🌅 🌄 🎇 🎆 🌇 🌆 🏙$VS 🌃 🌌 🌉 🌁",
        )),
        Category("活動", list(
            "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🏑 🥍 🏏 ⛳ 🏹 🎣 🥊 🥋 🎽 🛹 🛷 ⛸$VS 🥌 🎿",
            "⛷$VS 🏂 🏋$VS 🤼 🤸 ⛹$VS 🤺 🤾 🏌$VS 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚵 🚴 🏆 🥇 🥈 🥉 🏅 🎖$VS 🏵$VS 🎗$VS",
            "🎫 🎟$VS 🎪 🤹 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 🧩 ♟$VS 🎯 🎳 🎮 🎰",
            "🎃 🎄 🎉 🎊 🎈 🎁 🎀 🎋 🎍 🎎 🎏 🎐 🎑 🧧 ✨",
        )),
        Category("物", list(
            "⌚ 📱 📲 💻 ⌨$VS 🖥$VS 🖨$VS 🖱$VS 🖲$VS 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📽$VS 🎞$VS 📞 ☎$VS 📟 📠",
            "📺 📻 🎙$VS 🎚$VS 🎛$VS ⏱$VS ⏲$VS ⏰ 🕰$VS ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯$VS 🧯 🗑$VS 🛢$VS 💸 💵 💴 💶 💷",
            "💰 💳 🧾 💎 ⚖$VS 🔧 🔨 ⚒$VS 🛠$VS ⛏$VS 🔩 ⚙$VS 🧱 ⛓$VS 🧲 💣 🧨 🔪 🗡$VS ⚔$VS 🛡$VS 🚬 ⚰$VS ⚱$VS",
            "🏺 🔮 📿 🧿 💈 ⚗$VS 🔭 🔬 🕳$VS 💊 💉 🧬 🦠 🧫 🧪 🌡$VS 🧹 🧺 🧻 🚽 🚰 🚿 🛁 🧼 🧽 🧴",
            "🛎$VS 🔑 🗝$VS 🚪 🛋$VS 🛏$VS 🧸 🖼$VS 🛍$VS 🛒 📦 📫 📮 ✉$VS 📩 📨 📧 💌 📝 ✏$VS ✒$VS 🖋$VS 🖊$VS",
            "🖌$VS 🖍$VS 📁 📂 🗂$VS 📅 📆 🗒$VS 🗓$VS 📇 📈 📉 📊 📋 📌 📍 📎 🖇$VS 📏 📐 ✂$VS 🗃$VS 🗄$VS",
            "🔒 🔓 🔏 🔐 📕 📗 📘 📙 📚 📖 🔖 🔗 📰 🗞$VS 👓 🕶$VS 🥽 🥼 👔 👕 👖 🧣 🧤 🧥 🧦",
            "👗 👘 👙 👚 👛 👜 👝 🎒 👞 👟 🥾 🥿 👠 👡 👢 👑 👒 🎩 🎓 🧢 ⛑$VS 💄 💍 💼 🌂",
        )),
        Category("記号", list(
            "❤$VS 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣$VS 💕 💞 💓 💗 💖 💘 💝 💟 💯 💢 💥 💫 💦 💤 💬 💭 🗯$VS",
            "♨$VS 🆗 🆕 🆙 🆒 🆓 🆖 🆘 🆚 🈁 🈂$VS 🈷$VS 🈶 🈯 🉐 🈹 🈚 🈲 🉑 🈸 🈴 🈳 ㊗$VS ㊙$VS 🈺 🈵",
            "🔴 🟠 🟡 🟢 🔵 🟣 🟤 ⚫ ⚪ 🟥 🟧 🟨 🟩 🟦 🟪 🟫 ⬛ ⬜ 🔶 🔷 🔸 🔹 🔺 🔻 💠 🔘",
            "✅ ☑$VS ✔$VS ❌ ❎ ➕ ➖ ➗ ✖$VS ❓ ❔ ❕ ❗ ‼$VS ⁉$VS ⭕ 🚫 ⛔ 📛 🔞 📵 🚭 ⚠$VS 🚸 ♻$VS 🔰 🔱 ⚜$VS",
            "🔟 🔠 🔡 🔢 🔣 🔤 🅰$VS 🆎 🅱$VS 🆑 🅾$VS 🆔 Ⓜ$VS 🅿$VS ℹ$VS ⬆$VS ➡$VS ⬇$VS ⬅$VS ↩$VS ↪$VS ⤴$VS ⤵$VS",
            "🔃 🔄 🔙 🔚 🔛 🔜 🔝 ▶$VS ⏩ ⏭$VS ⏯$VS ◀$VS ⏪ ⏮$VS 🔼 ⏫ 🔽 ⏬ ⏸$VS ⏹$VS ⏺$VS 🎦 🔅 🔆",
            "📶 📳 📴 🔇 🔈 🔉 🔊 🔔 🔕 📣 📢 ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ⛎ 🏧 🚮 🚾 ♿",
            "🚹 🚺 🚻 🚼 🛂 🛃 🛄 🛅 🕐 🕑 🕒 🕓 🕔 🕕 🕖 🕗 🕘 🕙 🕚 🕛",
        ) + "#*0123456789".map(::keycap)),
        Category("旗", list("🏁 🚩 🎌 🏴 🏳$VS") + listOf("🏳$VS$ZWJ🌈") + list(
            "JP US GB CA AU NZ FR DE IT ES PT NL BE CH AT SE NO DK FI IS IE PL CZ HU GR TR RU UA",
            "CN KR TW HK SG TH VN PH MY ID IN PK BD NP LK MN AE SA IL EG ZA NG KE BR AR MX CL PE CO UN EU",
        ).map(::flag)),
    )

    private val symbolCatalog by lazy { symbols.flatMap { it.items }.toHashSet() }
    private val emojiCatalog by lazy { emoji.flatMap { it.items }.toHashSet() }

    /** Every entry of the symbol or the [emoji] palette: the only strings its 履歴 may hold. */
    fun catalog(emoji: Boolean): Set<String> = if (emoji) emojiCatalog else symbolCatalog

    /**
     * Entries the device font can draw ([hasGlyph], e.g. Paint.hasGlyph, which handles whole sequences). If the
     * check rejects everything (no font information, as in JVM tests) the list is kept as it is.
     */
    fun renderable(items: List<String>, hasGlyph: (String) -> Boolean): List<String> =
        items.filter(hasGlyph).ifEmpty { items }
}
