package com.fakhrillo.classickeyboard

enum class Icon { NONE, SHIFT, BACKSPACE, ENTER, EMOJI }

enum class Code { TEXT, SPACE, SHIFT, BACKSPACE, ENTER, LETTERS, SYMBOLS, SYMBOLS_ALT, EMOJI, EMOJI_NEXT }

/**
 * One key cap. [weight] is the width in key units (a letter key is 1.0);
 * anything wider than 1.0 is drawn as a pill instead of a circle.
 */
class Key(
    val label: String,
    val code: Code = Code.TEXT,
    val weight: Float = 1f,
    val icon: Icon = Icon.NONE,
    val red: Boolean = false,
)

typealias Page = List<List<Key>>

object Layouts {
    private const val SIDE = 1.3f
    private const val MODE = 1.4f
    private const val SPACE = 4.85f
    private const val ENTER = 2.35f

    private fun chars(s: String) = s.map { Key(it.toString()) }
    private fun emoji(vararg e: String) = e.map { Key(it) }

    private val backspace get() = Key("", Code.BACKSPACE, SIDE, Icon.BACKSPACE, red = true)
    private val space get() = Key("", Code.SPACE, SPACE)
    private val enter get() = Key("", Code.ENTER, ENTER, Icon.ENTER)

    val letters: Page = listOf(
        chars("QWERTYUIOP"),
        chars("ASDFGHJKL"),
        listOf(Key("", Code.SHIFT, SIDE, Icon.SHIFT)) + chars("ZXCVBNM") + backspace,
        listOf(Key("123", Code.SYMBOLS, MODE), Key("", Code.EMOJI, icon = Icon.EMOJI), space, enter),
    )

    private fun punctuation() = ".,?!'".map { Key(it.toString(), weight = 1.4f) }

    val symbols: Page = listOf(
        chars("1234567890"),
        chars("-/:;()$&@\""),
        listOf(Key("#+=", Code.SYMBOLS_ALT, SIDE)) + punctuation() + backspace,
        listOf(Key("ABC", Code.LETTERS, MODE), Key("", Code.EMOJI, icon = Icon.EMOJI), space, enter),
    )

    val symbolsAlt: Page = listOf(
        chars("[]{}#%^*+="),
        chars("_\\|~<>€£¥•"),
        listOf(Key("123", Code.SYMBOLS, SIDE)) + punctuation() + backspace,
        listOf(Key("ABC", Code.LETTERS, MODE), Key("", Code.EMOJI, icon = Icon.EMOJI), space, enter),
    )

    private fun emojiPage(n: Int, r1: List<Key>, r2: List<Key>, r3: List<Key>): Page = listOf(
        r1,
        r2,
        listOf(Key("123", Code.SYMBOLS, SIDE)) + r3 + backspace,
        listOf(Key("ABC", Code.LETTERS, MODE), Key("$n/3", Code.EMOJI_NEXT), space, enter),
    )

    val emojiPages: List<Page> = listOf(
        emojiPage(
            1,
            emoji("😀", "😂", "🥹", "😊", "😍", "😘", "😎", "🤔", "😅", "😭"),
            emoji("😡", "🥳", "😴", "🤗", "🙄", "😬", "🤯", "😇", "🤩"),
            emoji("👍", "👎", "👏", "🙏", "💪", "👋", "🤝"),
        ),
        emojiPage(
            2,
            emoji("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔", "✨", "🔥"),
            emoji("🎉", "🎁", "🎂", "🌹", "🌞", "🌙", "⭐", "☔", "⚡"),
            emoji("✅", "❌", "❓", "❗", "💯", "💤", "🎵"),
        ),
        emojiPage(
            3,
            emoji("🐶", "🐱", "🦊", "🐻", "🐼", "🦁", "🐸", "🐵", "🦄", "🐝"),
            emoji("🍎", "🍕", "🍔", "🍟", "🍩", "☕", "🍺", "🍷", "🍉"),
            emoji("⚽", "🏀", "🚗", "✈️", "🏠", "💻", "📱"),
        ),
    )
}
