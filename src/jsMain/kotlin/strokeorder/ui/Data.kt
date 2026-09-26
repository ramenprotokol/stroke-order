package strokeorder.ui

import strokeorder.engine.Point
import strokeorder.model.Catalog
import strokeorder.model.Group
import strokeorder.model.Kanji
import strokeorder.model.RefStroke

/**
 * Reads the stroke data file produced by scripts/build-data.mjs (KanjiVG-derived,
 * CC BY-SA 3.0). The file is ours and same-origin, but it is still checked field by
 * field so a broken deploy shows a clear message instead of a blank sheet.
 */
object Data {
    private const val MAX_BYTES = 2_000_000
    private const val MAX_KANJI = 500
    private const val MAX_STROKES = 40

    class Invalid(message: String) : Exception(message)

    fun parse(text: String): Catalog {
        if (text.length > MAX_BYTES) throw Invalid("stroke data is unexpectedly large")
        val root: dynamic = try { JSON.parse<dynamic>(text) } catch (e: Throwable) { throw Invalid("stroke data is not valid JSON") }
        if (root == null || root.format != 1) throw Invalid("unknown stroke data format")
        val groups = array(root.groups, "groups").map { g ->
            val id = str(g.id, "group id")
            Group(
                id = id,
                title = str(g.title, "group title"),
                jp = str(g.jp, "group label"),
                kanji = array(g.kanji, "kanji").map { k -> kanji(k, id) },
            )
        }
        val total = groups.sumOf { it.kanji.size }
        if (total == 0 || total > MAX_KANJI) throw Invalid("stroke data has $total characters")
        return Catalog(groups)
    }

    private fun kanji(k: dynamic, group: String): Kanji {
        val c = str(k.c, "character")
        val strokes = array(k.s, "strokes for $c")
        if (strokes.isEmpty() || strokes.size > MAX_STROKES) throw Invalid("$c has ${strokes.size} strokes")
        return Kanji(
            char = c,
            group = group,
            meaning = str(k.m, "meaning of $c"),
            on = array(k.on, "on readings").map { str(it, "reading") },
            kun = array(k.kun, "kun readings").map { str(it, "reading") },
            strokes = strokes.map { s ->
                val n: dynamic = s.n
                val label = if (n != null && jsTypeOf(n[0]) == "number" && jsTypeOf(n[1]) == "number") Point(n[0] as Double, n[1] as Double) else null
                try {
                    RefStroke(str(s.d, "path"), str(s.t, "stroke type"), label)
                } catch (e: IllegalArgumentException) {
                    throw Invalid("a stroke of $c could not be read: ${e.message}")
                }
            },
        )
    }

    private fun str(v: dynamic, what: String): String =
        if (jsTypeOf(v) == "string") v as String else throw Invalid("$what is missing")

    private fun array(v: dynamic, what: String): List<dynamic> {
        if (!js("Array").isArray(v) as Boolean) throw Invalid("$what is missing")
        val a = v.unsafeCast<Array<dynamic>>()
        return a.toList()
    }
}
