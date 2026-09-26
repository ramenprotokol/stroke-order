package strokeorder.model

/**
 * Kana helpers for search: katakana → hiragana, kana → Hepburn romaji, romaji in any of
 * the usual spellings (Hepburn, Kunrei, Nihon-shiki, with or without long-vowel marks)
 * → hiragana, and folding of long vowels so jū, juu and ju all meet.
 */
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

    /** Romaji syllables → hiragana, in every common spelling (し is shi and si, つ is tsu and tu…). */
    private val syllables: Map<String, String> = buildMap {
        fun row(consonant: String, kana: String) {
            "aiueo".forEachIndexed { i, v -> if (kana[i] != '_') put(consonant + v, kana[i].toString()) }
        }
        row("", "あいうえお")
        row("k", "かきくけこ"); row("g", "がぎぐげご"); row("s", "さしすせそ"); row("z", "ざじずぜぞ")
        row("t", "たちつてと"); row("d", "だぢづでど"); row("n", "なにぬねの"); row("h", "はひふへほ")
        row("b", "ばびぶべぼ"); row("p", "ぱぴぷぺぽ"); row("m", "まみむめも"); row("y", "や_ゆ_よ")
        row("r", "らりるれろ"); row("w", "わ___を")
        put("shi", "し"); put("chi", "ち"); put("tsu", "つ"); put("fu", "ふ"); put("ji", "じ")
        val small = mapOf('a' to "ゃ", 'u' to "ゅ", 'o' to "ょ")
        val yoon = mapOf(
            "ky" to "き", "gy" to "ぎ", "sh" to "し", "sy" to "し", "j" to "じ", "jy" to "じ", "zy" to "じ",
            "ch" to "ち", "ty" to "ち", "cy" to "ち", "dy" to "ぢ", "ny" to "に", "hy" to "ひ", "by" to "び",
            "py" to "ぴ", "my" to "み", "ry" to "り",
        )
        for ((c, kana) in yoon) for ((v, sm) in small) put(c + v, kana + sm)
    }

    /** Long vowels written with a macron or circumflex (Hepburn ū, Kunrei û) → two vowels. */
    private val longVowels = mapOf(
        'ā' to "aa", 'â' to "aa", 'ī' to "ii", 'î' to "ii", 'ū' to "uu", 'û' to "uu",
        'ē' to "ee", 'ê' to "ee", 'ō' to "ou", 'ô' to "ou",
    )

    /**
     * Hiragana for a romaji word in any common spelling, or null when it isn't romaji
     * ("water" is not). Doubled consonants become っ; n before a consonant, n' and nn become ん.
     */
    fun fromRomaji(input: String): String? {
        val s = buildString { for (c in input.lowercase()) append(longVowels[c] ?: c.toString()) }
        if (s.isEmpty()) return null
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            when {
                c == '\'' || c == '-' -> i++
                c == 'n' && (next == null || next == '\'' || (next !in "aiueoy" && next != 'n')) -> { out.append('ん'); i++ }
                c == 'n' && next == 'n' && (s.getOrNull(i + 2) == null || s[i + 2] !in "aiueoy") -> { out.append('ん'); i += 2 }
                c == 'n' && next == 'n' -> { out.append('ん'); i++ } // んな: the second n starts the next syllable
                c in "bcdfghjkmprstvwz" && (next == c || (c == 't' && next == 'c')) -> { out.append('っ'); i++ }
                else -> {
                    val match = (3 downTo 1).firstNotNullOfOrNull { n ->
                        if (i + n <= s.length) syllables[s.substring(i, i + n)]?.let { it to n } else null
                    } ?: return null
                    out.append(match.first)
                    i += match.second
                }
            }
        }
        return out.toString()
    }

    /**
     * Folds long vowels in Hepburn romaji so every way of writing them compares equal:
     * juu, jū and ju all become ju; shou, shō and sho become sho; oo becomes o. A doubled
     * i is left alone (ちい in chiisai is two beats, not a long vowel), and so is ei.
     */
    fun foldLongVowels(romaji: String): String = buildString {
        for (c in romaji) {
            val prev = lastOrNull()
            val repeat = c in "aueo" && (c == prev || (c == 'u' && prev == 'o'))
            if (!repeat) append(c)
        }
    }
}
