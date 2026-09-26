package strokeorder

import strokeorder.model.Kanji
import strokeorder.model.RefStroke
import java.io.File

/** The committed KanjiVG snapshot (data/kanjivg), read straight from the SVG files. */
object KanjiSet {
    private val pathRe = Regex("""<path [^>]*?kvg:type="([^"]*)"[^>]*? d="([^"]+)"""")

    val all: List<Kanji> by lazy {
        val files = File("data/kanjivg").listFiles { f -> f.name.matches(Regex("[0-9a-f]{5}\\.svg")) }!!.sortedBy { it.name }
        files.map { f ->
            val strokes = pathRe.findAll(f.readText()).map { RefStroke(it.groupValues[2], it.groupValues[1], null) }.toList()
            val ch = String(Character.toChars(f.name.substring(0, 5).toInt(16)))
            Kanji(ch, "", "", emptyList(), emptyList(), strokes)
        }
    }

    private val byChar by lazy { all.associateBy { it.char } }

    operator fun get(char: String): Kanji = byChar.getValue(char)
}
