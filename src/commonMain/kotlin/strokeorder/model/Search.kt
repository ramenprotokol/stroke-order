package strokeorder.model

/**
 * Character search by English meaning, reading (kana or romaji) or the character itself.
 * Queries are capped at [MAX_QUERY] characters; longer input is cut, never processed whole.
 * Input is NFKC-folded first (full-width Latin and half-width katakana become ordinary),
 * romaji may be Hepburn or Kunrei (tu, si, zi, hu…), and long vowels may be written
 * with a macron, doubled or not at all (jū, juu, ju).
 */
object Search {
    const val MAX_QUERY = 40

    private class Keys(
        val words: List<String>,
        val phrases: List<String>,
        val kana: List<String>,
        val romaji: List<String>,
        val folded: List<String>,
    )

    private val cache = HashMap<Kanji, Keys>()

    private fun keysOf(k: Kanji): Keys = cache.getOrPut(k) {
        val phrases = k.meaning.lowercase().split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val words = phrases.flatMap { it.split(' ') }.filter { it.isNotEmpty() }
        val readings = (k.on + k.kun).flatMap { r ->
            val h = Kana.toHiragana(r)
            listOf(h.replace(".", ""), h.substringBefore('.'))
        }.distinct()
        val romaji = readings.map { Kana.toRomaji(it) }.distinct()
        Keys(words, phrases, readings, romaji, romaji.map { Kana.foldLongVowels(it) }.distinct())
    }

    fun normalize(query: String): String =
        nfkc(query.take(MAX_QUERY)).take(MAX_QUERY).trim().lowercase().replace(Regex("\\s+"), " ")

    fun run(catalog: Catalog, query: String): List<Kanji> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        catalog[q]?.let { return listOf(it) }
        // Pasted kanji ("山川"): return every curated character in the query, in order.
        val pasted = q.mapNotNull { catalog[it.toString()] }.distinct()
        if (pasted.isNotEmpty()) return pasted
        val kanaQuery = q.any { Kana.isKana(it) }
        val hq = Kana.toHiragana(q)
        // The query as Hepburn romaji and with long vowels folded, whichever way it was typed.
        val hepburn = (if (kanaQuery) hq else Kana.fromRomaji(q))?.let { Kana.toRomaji(it) }
        val folded = hepburn?.let { Kana.foldLongVowels(it) }
        val scored = catalog.all.mapNotNull { k ->
            val keys = keysOf(k)
            val reading = when {
                kanaQuery && keys.kana.any { it == hq } -> 3
                hepburn != null && (keys.romaji.any { it == hepburn } || keys.folded.any { it == folded }) -> 3
                kanaQuery && hq.length >= 2 && keys.kana.any { it.startsWith(hq) } -> 1
                hepburn != null && hepburn.length >= 3 && (keys.romaji.any { it.startsWith(hepburn) } || keys.folded.any { it.startsWith(folded!!) }) -> 1
                else -> 0
            }
            val meaning = if (kanaQuery) 0 else when {
                keys.words.any { it == q } || keys.phrases.any { it == q } -> 3
                keys.phrases.any { it.startsWith(q) } -> 2
                q.length >= 2 && keys.words.any { it.startsWith(q) } -> 1
                else -> 0
            }
            val score = maxOf(reading, meaning)
            if (score > 0) k to score else null
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }
}
