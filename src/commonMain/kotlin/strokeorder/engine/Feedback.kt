package strokeorder.engine

import strokeorder.model.Kanji

/** Plain-English names and descriptions for strokes, built from KanjiVG stroke types. */
object Describe {
    /** KanjiVG uses the CJK Strokes block (U+31C0–U+31E3) to type each stroke. */
    private val names = mapOf(
        '㇀' to "rising stroke", '㇁' to "curved hook", '㇂' to "slanted hook", '㇃' to "bent hook",
        '㇄' to "down-and-across turn", '㇅' to "zigzag", '㇆' to "hooked turn", '㇇' to "across-and-sweep turn",
        '㇈' to "curving hooked turn", '㇉' to "zigzag hook", '㇊' to "turn with a rise", '㇋' to "zigzag sweep",
        '㇌' to "sweep with a hook", '㇍' to "curving turn", '㇎' to "zigzag", '㇏' to "right sweep",
        '㇐' to "horizontal", '㇑' to "vertical", '㇒' to "left sweep", '㇓' to "long left sweep",
        '㇔' to "dot", '㇕' to "across-and-down turn", '㇖' to "horizontal hook", '㇗' to "down-and-across turn",
        '㇘' to "zigzag turn", '㇙' to "vertical with a rise", '㇚' to "vertical hook", '㇛' to "sweep-and-dot",
        '㇜' to "sweep-and-turn", '㇝' to "flat sweep", '㇞' to "zigzag", '㇟' to "bent hook",
        '㇠' to "hooked turn", '㇡' to "zigzag hook", '㇢' to "sweeping hook", '㇣' to "circle",
    )

    /** Stroke types that change direction mid-stroke: worth reminding people not to lift. */
    private val oneMovement = "㇄㇅㇆㇇㇈㇉㇊㇋㇌㇍㇎㇕㇖㇗㇘㇛㇜㇞㇟㇠㇡".toSet()

    fun typeName(type: String): String = type.firstOrNull()?.let { names[it] } ?: "stroke"

    fun isOneMovement(type: String): Boolean = type.firstOrNull()?.let { it in oneMovement } ?: false

    /**
     * A name for stroke [index] that says where it sits in the character, e.g. "the
     * vertical down the middle", "the horizontal across the middle", "the upper-left dot",
     * "the roof" or "the second left sweep". Every stroke of a character gets a different
     * description. Positions are judged against every stroke's straight stretches, the
     * horizontal and vertical parts of turns included, and against the whole character's
     * extent, not only against strokes of the same type.
     */
    fun stroke(kanji: Kanji, index: Int): String = Placement.describe(kanji)[index]

    /** How a stroke travels: "runs left to right" or, for turns, "starts at the top left". */
    fun travel(kanji: Kanji, index: Int): String {
        val s = kanji.strokes[index]
        val pts = s.points
        val turning = Geometry.totalAbsTurning(Geometry.resample(pts, 24))
        val compass = Compass.of(pts)
        if (turning < 1.0 && compass != null) return "runs ${compass.phrase}"
        val box = Geometry.bounds(pts)
        val p = pts.first()
        val fx = if (box.width < 1e-6) 0.5 else (p.x - box.minX) / box.width
        val fy = if (box.height < 1e-6) 0.5 else (p.y - box.minY) / box.height
        val v = when { fy < 0.34 -> "top"; fy > 0.66 -> "bottom"; else -> "" }
        val h = when { fx < 0.34 -> "left"; fx > 0.66 -> "right"; else -> "" }
        val where = listOf(v, h).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "middle" }
        return "starts at the $where"
    }

}

/** Gentle, specific sentences for each verdict. */
object Feedback {
    fun forVerdict(kanji: Kanji, v: Verdict, slips: Int = 0): String = when (v) {
        is Verdict.Accepted ->
            if (v.complete) {
                val n = kanji.strokeCount
                val all = if (n == 1) "The stroke is" else "All $n strokes are"
                if (slips == 0) "$all in order, first time." else "$all in order."
            } else {
                "Stroke ${v.index + 1} of ${kanji.strokeCount} — ${Describe.stroke(kanji, v.index)}."
            }
        is Verdict.WrongDirection ->
            "Right stroke, other way round — stroke ${v.index + 1} ${Describe.travel(kanji, v.index)}."
        is Verdict.OutOfOrder ->
            "That's stroke ${v.drawn + 1} — stroke ${v.expected + 1} comes first (${Describe.stroke(kanji, v.expected)})."
        is Verdict.AlreadyWritten ->
            "Stroke ${v.drawn + 1} is already written — next is stroke ${v.expected + 1} (${Describe.stroke(kanji, v.expected)})."
        is Verdict.Unrecognized -> {
            val type = kanji.strokes[v.expected].type
            val hint = if (Describe.isOneMovement(type)) " It's one movement — don't lift the brush at the corner." else ""
            "Not quite. Stroke ${v.expected + 1} is ${Describe.stroke(kanji, v.expected)}; it ${Describe.travel(kanji, v.expected)}.$hint"
        }
        Verdict.TooShort -> "That was a tap — press and drag to make a stroke."
        Verdict.AlreadyComplete -> "This one is finished. Clear the sheet to write it again."
    }

    /** The prompt shown before a stroke is drawn. */
    fun prompt(kanji: Kanji, next: Int): String =
        if (next == 0) "Begin with stroke 1 — ${Describe.stroke(kanji, 0)}."
        else "Next: stroke ${next + 1} of ${kanji.strokeCount}."
}
