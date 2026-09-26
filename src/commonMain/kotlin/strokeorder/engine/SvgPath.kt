package strokeorder.engine

/**
 * Turns an SVG path `d` string (as used by KanjiVG) into a polyline by flattening its
 * curves. Supports M/L/H/V/C/S/Q/T/Z in absolute and relative forms. Arcs (A) are not
 * used by KanjiVG and are rejected.
 */
object SvgPath {
    /** Hard cap so a malformed or hostile path cannot allocate without bound. */
    const val MAX_LENGTH = 4000

    fun parse(d: String, segmentsPerCurve: Int = 12): List<Point> {
        require(d.length <= MAX_LENGTH) { "path data longer than $MAX_LENGTH characters" }
        val tokens = tokenize(d)
        val out = ArrayList<Point>()
        var i = 0
        var cmd = ' '
        var cur = Point(0.0, 0.0)
        var start = cur
        var lastCubicCtrl: Point? = null
        var lastQuadCtrl: Point? = null

        fun num(): Double {
            val t = tokens.getOrNull(i) ?: throw IllegalArgumentException("path ends early after '$cmd'")
            require(t is Token.Num) { "expected a number after '$cmd'" }
            i++
            return t.value
        }
        fun add(p: Point) {
            if (out.isEmpty() || out.last() != p) out.add(p)
        }
        fun cubic(p0: Point, c1: Point, c2: Point, p3: Point) {
            for (s in 1..segmentsPerCurve) {
                val t = s.toDouble() / segmentsPerCurve
                val u = 1 - t
                add(
                    Point(
                        u * u * u * p0.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * p3.x,
                        u * u * u * p0.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * p3.y,
                    ),
                )
            }
        }
        fun quad(p0: Point, c: Point, p2: Point) {
            for (s in 1..segmentsPerCurve) {
                val t = s.toDouble() / segmentsPerCurve
                val u = 1 - t
                add(Point(u * u * p0.x + 2 * u * t * c.x + t * t * p2.x, u * u * p0.y + 2 * u * t * c.y + t * t * p2.y))
            }
        }

        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Token.Cmd) {
                cmd = t.c
                i++
            } else if (cmd == ' ') {
                throw IllegalArgumentException("expected a path command")
            }
            val rel = cmd.isLowerCase()
            val base = if (rel) cur else Point(0.0, 0.0)
            when (cmd.uppercaseChar()) {
                'M' -> {
                    cur = Point(base.x + num(), base.y + num())
                    start = cur
                    add(cur)
                    // Further coordinate pairs after a moveto are implicit linetos.
                    cmd = if (rel) 'l' else 'L'
                    lastCubicCtrl = null; lastQuadCtrl = null
                }
                'L' -> { cur = Point(base.x + num(), base.y + num()); add(cur); lastCubicCtrl = null; lastQuadCtrl = null }
                'H' -> { cur = Point((if (rel) cur.x else 0.0) + num(), cur.y); add(cur); lastCubicCtrl = null; lastQuadCtrl = null }
                'V' -> { cur = Point(cur.x, (if (rel) cur.y else 0.0) + num()); add(cur); lastCubicCtrl = null; lastQuadCtrl = null }
                'C' -> {
                    val c1 = Point(base.x + num(), base.y + num())
                    val c2 = Point(base.x + num(), base.y + num())
                    val p = Point(base.x + num(), base.y + num())
                    cubic(cur, c1, c2, p)
                    lastCubicCtrl = c2; lastQuadCtrl = null; cur = p
                }
                'S' -> {
                    val c1 = lastCubicCtrl?.let { cur * 2.0 - it } ?: cur
                    val c2 = Point(base.x + num(), base.y + num())
                    val p = Point(base.x + num(), base.y + num())
                    cubic(cur, c1, c2, p)
                    lastCubicCtrl = c2; lastQuadCtrl = null; cur = p
                }
                'Q' -> {
                    val c = Point(base.x + num(), base.y + num())
                    val p = Point(base.x + num(), base.y + num())
                    quad(cur, c, p)
                    lastQuadCtrl = c; lastCubicCtrl = null; cur = p
                }
                'T' -> {
                    val c = lastQuadCtrl?.let { cur * 2.0 - it } ?: cur
                    val p = Point(base.x + num(), base.y + num())
                    quad(cur, c, p)
                    lastQuadCtrl = c; lastCubicCtrl = null; cur = p
                }
                'Z' -> { cur = start; add(cur); lastCubicCtrl = null; lastQuadCtrl = null; cmd = ' ' }
                else -> throw IllegalArgumentException("unsupported path command '$cmd'")
            }
        }
        require(out.isNotEmpty()) { "path has no points" }
        return out
    }

    private sealed class Token {
        data class Cmd(val c: Char) : Token()
        data class Num(val value: Double) : Token()
    }

    /** Splits "M11,54.25c3.19,0.62-5.12.5" into commands and numbers (handles "-" and "." as separators). */
    private fun tokenize(d: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < d.length) {
            val ch = d[i]
            when {
                ch.isWhitespace() || ch == ',' -> i++
                ch in "MmLlHhVvCcSsQqTtZz" -> { out.add(Token.Cmd(ch)); i++ }
                ch == '-' || ch == '+' || ch == '.' || ch.isDigit() -> {
                    val startIdx = i
                    if (ch == '-' || ch == '+') i++
                    var sawDot = false
                    var sawDigit = false
                    while (i < d.length) {
                        val c = d[i]
                        if (c.isDigit()) { sawDigit = true; i++ }
                        else if (c == '.' && !sawDot) { sawDot = true; i++ }
                        else break
                    }
                    if (i < d.length && (d[i] == 'e' || d[i] == 'E')) {
                        i++
                        if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
                        while (i < d.length && d[i].isDigit()) i++
                    }
                    require(sawDigit) { "malformed number at $startIdx" }
                    val v = d.substring(startIdx, i).toDouble()
                    require(v.isFinite()) { "number out of range at $startIdx" }
                    out.add(Token.Num(v))
                }
                else -> throw IllegalArgumentException("unexpected '$ch' in path data")
            }
        }
        return out
    }
}
