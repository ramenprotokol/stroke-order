package strokeorder.model

/**
 * Character search by English meaning, reading (kana or romaji) or the character itself.
 * Queries are capped at [MAX_QUERY] characters; longer input is cut, never processed whole.
 */
object Search {
    const val MAX_QUERY = 40

    private class Keys(val words: List<String>, val phrases: List<String>, val kana: List<String>, val romaji: List<String>)

    private val cache = HashMap<Kanji, Keys>()

    private fun keysOf(k: Kanji): Keys = cache.getOrPut(k) {
        val phrases = k.meaning.lowercase().split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val words = phrases.flatMap { it.split(' ') }.filter { it.isNotEmpty() }
        val readings = (k.on + k.kun).flatMap { r ->
            val h = Kana.toHiragana(r)
            listOf(h.replace(".", ""), h.substringBefore('.'))
        }.distinct()
        Keys(words, phrases, readings, readings.map { Kana.toRomaji(it) }.distinct())
    }

    fun normalize(query: String): String =
        query.take(MAX_QUERY).trim().lowercase().replace(Regex("\\s+"), " ")

    fun run(catalog: Catalog, query: String): List<Kanji> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        catalog[q]?.let { return listOf(it) }
        // Pasted kanji ("山川"): return every curated character in the query, in order.
        val pasted = q.mapNotNull { catalog[it.toString()] }.distinct()
        if (pasted.isNotEmpty()) return pasted
        val kanaQuery = q.any { Kana.isKana(it) }
        val hq = Kana.toHiragana(q)
        val scored = catalog.all.mapNotNull { k ->
            val keys = keysOf(k)
            val score = if (kanaQuery) {
                when {
                    keys.kana.any { it == hq } -> 3
                    hq.length >= 2 && keys.kana.any { it.startsWith(hq) } -> 1
                    else -> 0
                }
            } else {
                maxOf(
                    when {
                        keys.words.any { it == q } || keys.phrases.any { it == q } -> 3
                        keys.phrases.any { it.startsWith(q) } -> 2
                        q.length >= 2 && keys.words.any { it.startsWith(q) } -> 1
                        else -> 0
                    },
                    when {
                        keys.romaji.any { it == q } -> 3
                        q.length >= 3 && keys.romaji.any { it.startsWith(q) } -> 1
                        else -> 0
                    },
                )
            }
            if (score > 0) k to score else null
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }
}
