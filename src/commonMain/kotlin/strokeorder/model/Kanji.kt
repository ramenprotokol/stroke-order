package strokeorder.model

import strokeorder.engine.Geometry
import strokeorder.engine.Point
import strokeorder.engine.SvgPath

/**
 * One reference stroke from KanjiVG: its path data, its KanjiVG stroke type (e.g. "㇐"
 * for a horizontal) and where KanjiVG places its stroke number.
 */
class RefStroke(val pathData: String, val type: String, val label: Point?) {
    /** The stroke as a flattened polyline in the 109 × 109 frame. */
    val points: List<Point> = SvgPath.parse(pathData)
    val length: Double = Geometry.pathLength(points)
}

class Kanji(
    val char: String,
    val group: String,
    val meaning: String,
    val on: List<String>,
    val kun: List<String>,
    val strokes: List<RefStroke>,
) {
    val strokeCount get() = strokes.size

    /** First meaning, for compact labels ("sun; day" → "sun"). */
    val shortMeaning get() = meaning.substringBefore(';').trim()
}

class Group(val id: String, val title: String, val jp: String, val kanji: List<Kanji>)

class Catalog(val groups: List<Group>) {
    val all: List<Kanji> = groups.flatMap { it.kanji }
    private val byChar = all.associateBy { it.char }

    operator fun get(char: String): Kanji? = byChar[char]
    val first: Kanji get() = all.first()

    fun indexOf(k: Kanji) = all.indexOf(k)
    fun next(k: Kanji): Kanji = all[(indexOf(k) + 1) % all.size]

    /** Search by meaning (English), reading (kana or romaji) or the character itself. */
    fun search(query: String): List<Kanji> = Search.run(this, query)
}
