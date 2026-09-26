package strokeorder.model

/** Kana helpers for search: katakana → hiragana, and kana → Hepburn romaji. */
object Kana {
    private const val KATA_START = 0x30A1 // ァ
    private const val KATA_END = 0x30F6 // ヶ
    private const val OFFSET = 0x60

    fun isKana(c: Char) = c.code in 0x3041..0x3096 || c.code in KATA_START..0x30FC

    fun toHiragana(s: String): String = buildString(s.length) {
        for (c in s) append(if (c.code in KATA_START..KATA_END) (c.code - OFFSET).toChar() else c)
    }

    private val base: Map<Char, String> = buildMap {
        fun row(kana: String, vararg roma: String) = kana.forEachIndexed { i, k -> put(k, roma[i]) }
        row("あいうえお", "a", "i", "u", "e", "o")
        row("かきくけこ", "ka", "ki", "ku", "ke", "ko")
        row("がぎぐげご", "ga", "gi", "gu", "ge", "go")
        row("さしすせそ", "sa", "shi", "su", "se", "so")
        row("ざじずぜぞ", "za", "ji", "zu", "ze", "zo")
        row("たちつてと", "ta", "chi", "tsu", "te", "to")
        row("だぢづでど", "da", "ji", "zu", "de", "do")
        row("なにぬねの", "na", "ni", "nu", "ne", "no")
        row("はひふへほ", "ha", "hi", "fu", "he", "ho")
        row("ばびぶべぼ", "ba", "bi", "bu", "be", "bo")
        row("ぱぴぷぺぽ", "pa", "pi", "pu", "pe", "po")
        row("まみむめも", "ma", "mi", "mu", "me", "mo")
        row("やゆよ", "ya", "yu", "yo")
        row("らりるれろ", "ra", "ri", "ru", "re", "ro")
        row("わゐゑを", "wa", "i", "e", "o")
        row("ぁぃぅぇぉゎ", "a", "i", "u", "e", "o", "wa")
        put('ん', "n")
    }
    private val smallY = mapOf('ゃ' to "a", 'ゅ' to "u", 'ょ' to "o")

    /**
     * Hepburn romaji for a kana string (katakana is accepted too). Characters that are
     * not kana pass through unchanged, except the okurigana dot, which is dropped.
     */
    fun toRomaji(input: String): String {
        val s = toHiragana(input)
        val out = StringBuilder()
        var geminate = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            var syllable: String? = base[c]
            if (syllable != null && i + 1 < s.length && s[i + 1] in smallY && syllable.endsWith("i") && syllable.length > 1) {
                val vowel = smallY.getValue(s[i + 1])
                val stem = syllable.dropLast(1)
                syllable = if (stem == "sh" || stem == "ch" || stem == "j") stem + vowel else stem + "y" + vowel
                i++
            }
            when {
                c == 'っ' -> geminate = true
                c == 'ー' -> out.lastOrNull()?.takeIf { it in "aeiou" }?.let { out.append(it) }
                c == '.' -> {}
                syllable != null -> {
                    if (geminate && syllable[0] !in "aeiou") out.append(if (syllable.startsWith("ch")) 't' else syllable[0])
                    geminate = false
                    out.append(syllable)
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
