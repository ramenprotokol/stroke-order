package strokeorder.engine

import strokeorder.model.Kanji
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Puts each stroke of a character into words by where it sits, so feedback can say
 * "the vertical down the middle" rather than just "the vertical".
 *
 * Every stroke is cut into its straight horizontal and vertical stretches, so the top
 * of 口's turn counts as a horizontal and its right side as a vertical. A horizontal is
 * placed among all the horizontal stretches above and below it, a vertical among all the
 * vertical stretches beside it, and the words are checked against the whole character's
 * extent ("across the middle" only when it really is). Other strokes (dots, sweeps,
 * turns) are told apart from strokes of the same kind by position on both axes, by
 * length, or failing that by order ("the second left sweep"). No two strokes of one
 * character get the same description.
 */
internal object Placement {
    /** Shortest straight stretch (units) that counts as a horizontal or vertical line. */
    private const val MIN_RUN = 6.0

    /** How far a stretch may lean (rise over run) and still count as level or plumb (about 22°). */
    private const val AXIS_SLOPE = 0.4

    /** Two stretches are stacked when they share at least this much of the shorter one. */
    private const val OVERLAP = 0.4

    /**
     * A straight stretch of a sweep, dot or turn only counts as a neighbour when it is at
     * least this share of the described stroke's length (the upright start of 川's sweep
     * counts; the brief upright tip of 先's first sweep does not).
     */
    private const val NEIGHBOUR_SHARE = 0.5

    /** Stretches whose heights (or left-right positions) differ by less than this are on one level. */
    private const val LEVEL_GAP = 4.0

    /** A gap of this share of the character's size separates two clusters of strokes. */
    private const val CLUSTER_GAP = 0.12

    /** A line must span this share of the character to be "across" or "down" the middle. */
    private const val SPAN = 0.45

    private const val HORIZONTAL = "horizontal"
    private const val VERTICAL = "vertical"
    private const val MIDDLE = "middle"

    /** One straight stretch of stroke [stroke]. */
    private class Run(val stroke: Int, val horizontal: Boolean, val box: Box) {
        val cx get() = (box.minX + box.maxX) / 2
        val cy get() = (box.minY + box.maxY) / 2
        /** Extent along the line (width for a horizontal, height for a vertical). */
        val along get() = if (horizontal) box.width else box.height
        /** Position across the line (height for a horizontal, left-right for a vertical). */
        val across get() = if (horizontal) cy else cx
        fun overlap(o: Run): Double =
            if (horizontal) min(box.maxX, o.box.maxX) - max(box.minX, o.box.minX)
            else min(box.maxY, o.box.maxY) - max(box.minY, o.box.minY)
    }

    /** The words that place one stroke, rendered as "the [ordinal] [size] [row-col] name [post]". */
    private class Words {
        var row: String? = null
        var col: String? = null
        var ordinal: String? = null
        var post: String? = null
        var size: String? = null
        var fixed: String? = null

        fun render(name: String): String = fixed ?: listOfNotNull(
            "the",
            ordinal,
            size,
            listOfNotNull(row, col).joinToString("-").ifEmpty { null },
            name,
            post,
        ).joinToString(" ")

        fun clear() {
            row = null; col = null; ordinal = null; post = null; size = null
        }
    }

    /** The character's extent, for "is this really in the middle?" checks. */
    private class Frame(val box: Box) {
        val w = max(box.width, 1.0)
        val h = max(box.height, 1.0)
        fun u(x: Double) = (x - box.minX) / w
        fun v(y: Double) = (y - box.minY) / h
    }

    // The app asks about one character at a time, so one cached answer is enough.
    private var cachedFor: Kanji? = null
    private var cached: List<String> = emptyList()

    fun describe(kanji: Kanji): List<String> {
        if (cachedFor !== kanji) {
            cached = compute(kanji)
            cachedFor = kanji
        }
        return cached
    }

    private fun compute(kanji: Kanji): List<String> {
        val strokes = kanji.strokes
        if (strokes.isEmpty()) return emptyList()
        val names = strokes.map { Describe.typeName(it.type) }
        // Evenly spaced points, so a curve drawn with many path points doesn't pull the centre.
        val even = strokes.map { Geometry.resample(it.points, 32) }
        val frame = Frame(Geometry.bounds(even.flatten()))
        val straight = strokes.indices.flatMap { runsOf(it, strokes[it].points) }
        val words = List(strokes.size) { Words() }

        // The main straight stretch of each horizontal or vertical stroke.
        val main = HashMap<Int, Run>()
        for (i in strokes.indices) {
            val horizontal = names[i] == HORIZONTAL
            if (!horizontal && names[i] != VERTICAL) continue
            main[i] = straight.filter { it.stroke == i && it.horizontal == horizontal }.maxByOrNull { it.along }
                ?: Run(i, horizontal, Geometry.bounds(even[i]))
        }
        // A slanted short "vertical" (艹's) has no straight stretch; it still counts, as its whole extent.
        val runs = straight + main.values.filter { m -> straight.none { it === m } }

        // The roof: a horizontal hook spanning the character with a mark above it (宀, 冖).
        val roof = strokes.indices.firstOrNull { i ->
            val top = runs.filter { it.stroke == i && it.horizontal }.maxByOrNull { it.along }
            names[i] == "horizontal hook" && top != null && top.along >= 0.6 * frame.w &&
                strokes.indices.any { j ->
                    val b = Geometry.bounds(even[j])
                    j != i && b.maxY <= top.cy + 1.0 && b.minX >= top.box.minX - 2.0 && b.maxX <= top.box.maxX + 2.0
                }
        }
        if (roof != null) words[roof].fixed = "the roof"

        val groups = strokes.indices.filter { it != roof }.groupBy { names[it] }
        for ((name, group) in groups) {
            when (name) {
                HORIZONTAL, VERTICAL -> placeLines(name == HORIZONTAL, group, main, runs, names, words, frame)
                else -> if (group.size >= 2) placeOthers(name, group, even, words, frame)
            }
        }
        return ensureUnique(strokes.indices.map { words[it].render(names[it]) }, names)
    }

    /** Horizontals by height among every horizontal stretch they share a span with; verticals likewise. */
    private fun placeLines(
        horizontal: Boolean,
        group: List<Int>,
        main: Map<Int, Run>,
        runs: List<Run>,
        names: List<String>,
        words: List<Words>,
        frame: Frame,
    ) {
        for (i in group) {
            val m = main.getValue(i)
            val stack = runs.filter {
                it.horizontal == horizontal && it.stroke != i &&
                    it.overlap(m) >= OVERLAP * min(it.along, m.along) &&
                    (names[it.stroke] == HORIZONTAL || names[it.stroke] == VERTICAL || it.along >= NEIGHBOUR_SHARE * m.along)
            } + m
            val levels = levels(stack.map { it.across })
            val n = levels.max() + 1
            val r = levels.last()
            val word = words[i]
            // Where the line sits across the character (0 = top or left, 1 = bottom or right).
            val at = if (horizontal) frame.v(m.cy) else frame.u(m.cx)
            val spans = m.along >= SPAN * (if (horizontal) frame.w else frame.h)
            val (first, last) = if (horizontal) "top" to "bottom" else "left" to "right"
            val (nearFirst, nearLast) = if (horizontal) "upper" to "lower" else "left" to "right"
            val middlePost = if (horizontal) "across the middle" else "down the middle"
            fun side(s: String?) = if (horizontal) word.row = s else word.col = s
            fun middle() = if (spans) word.post = middlePost else side(MIDDLE)
            when {
                n < 2 -> if (group.size >= 2) when {
                    at <= 0.25 -> side(first)
                    at >= 0.75 -> side(last)
                    !horizontal && at in 0.4..0.6 -> middle()
                }
                // Of two lines, one may sit in the middle of the character (土's upper horizontal,
                // 正's upright) even though the other is to one side of it.
                n == 2 && at in 0.42..0.58 && (if (r == 0) at >= 0.45 else at <= 0.55) -> middle()
                r == 0 -> side(if (horizontal && at <= 0.25) first else nearFirst)
                r == n - 1 -> side(if (horizontal && at >= 0.75) last else nearLast)
                n % 2 == 1 && r == n / 2 -> if (at in 0.3..0.7) middle() else side(MIDDLE)
                // Count from the nearer end: "the second horizontal from the bottom".
                r <= n - 1 - r -> { word.ordinal = ordinal(r + 1); word.post = "from the $first" }
                else -> { word.ordinal = ordinal(n - r); word.post = "from the $last" }
            }
        }
        val name = if (horizontal) HORIZONTAL else VERTICAL
        // Side-by-side lines on one level (森's two lower horizontals) should read alike, then be
        // told apart below; if their neighbours gave them different words, drop those words.
        val level = levels(group.map { main.getValue(it).across })
        for (same in group.indices.groupBy { level[it] }.values.map { ks -> ks.map { group[it] } }) {
            if (same.size < 2 || same.map { words[it].render(name) }.distinct().size == 1) continue
            val sideBySide = same.all { a -> same.all { b -> a == b || main.getValue(a).overlap(main.getValue(b)) <= 0.0 } }
            if (sideBySide) same.forEach { words[it].clear() }
        }
        // Lines that still read the same are told apart on the other axis: 林's two horizontals
        // become the left and the right one, 出's right-hand verticals the upper and the lower.
        for (tied in group.groupBy { words[it].render(name) }.values.filter { it.size >= 2 }) {
            val values = tied.map { if (horizontal) main.getValue(it).cx else main.getValue(it).cy }
            val labels = clusterLabels(values, if (horizontal) frame.w * CLUSTER_GAP else frame.h * CLUSTER_GAP, if (horizontal) SIDES else HEIGHTS)
            if (labels != null && labels.distinct().size == tied.size) {
                tied.forEachIndexed { k, i ->
                    val w = words[i]
                    // A shared "middle" says nothing once the other axis tells them apart.
                    if (horizontal) {
                        if (w.row == MIDDLE) w.row = null
                        w.col = labels[k]
                    } else {
                        if (w.col == MIDDLE) w.col = null
                        w.row = labels[k]
                    }
                }
            } else {
                tied.forEachIndexed { k, i -> words[i].clear(); words[i].ordinal = ordinal(k + 1) }
            }
        }
    }

    /** Dots, sweeps and turns: by position among strokes of the same kind, by length, or by order. */
    private fun placeOthers(name: String, group: List<Int>, even: List<List<Point>>, words: List<Words>, frame: Frame) {
        val lengths = group.map { Geometry.pathLength(even[it]) }
        fun bySize(): Boolean {
            if (group.size != 2 || lengths.min() / lengths.max() > 0.62) return false
            group.forEachIndexed { k, i -> words[i].size = if (lengths[k] == lengths.min()) "short" else "long" }
            return true
        }
        fun byOrder() = group.forEachIndexed { k, i -> words[i].ordinal = ordinal(k + 1) }
        // "the left left sweep" helps nobody: sweeps named for a side go by length or by order.
        if (name.split(' ').any { it == "left" || it == "right" }) {
            if (!bySize()) byOrder()
            return
        }
        val centres = group.map { Geometry.centroid(even[it]) }
        val labels = splitByPosition(centres, frame)
        if (labels != null) {
            group.forEachIndexed { k, i -> words[i].row = labels[k].first; words[i].col = labels[k].second }
        } else if (!bySize()) {
            byOrder()
        }
    }

    /**
     * Splits same-kind strokes by position: first along the axis with the widest gap, then
     * each part along the other axis. 雨's four dots become upper-left, lower-left,
     * upper-right and lower-right; 学's three become upper-left, upper-right and lower.
     * Returns (row, column) words, or null when position can't tell them all apart.
     */
    private fun splitByPosition(centres: List<Point>, frame: Frame): List<Pair<String?, String?>>? {
        fun gapOf(values: List<Double>, extent: Double) = values.sorted().zipWithNext { a, b -> b - a }.maxOrNull()?.div(extent) ?: 0.0
        val xs = centres.map { it.x }
        val ys = centres.map { it.y }
        val byRow = clusterLabels(ys, frame.h * CLUSTER_GAP, HEIGHTS)
        val byCol = clusterLabels(xs, frame.w * CLUSTER_GAP, SIDES)
        // Split first along the axis that falls into fewer, cleaner groups; on a tie, the wider gap.
        val rowsFirst = when {
            byRow == null -> false
            byCol == null -> true
            byRow.distinct().size != byCol.distinct().size -> byRow.distinct().size < byCol.distinct().size
            else -> gapOf(ys, frame.h) >= gapOf(xs, frame.w)
        }
        val primary = (if (rowsFirst) byRow else byCol) ?: return null
        val out = MutableList<Pair<String?, String?>>(centres.size) { k -> if (rowsFirst) primary[k] to null else null to primary[k] }
        for (members in centres.indices.groupBy { primary[it] }.values) {
            if (members.size < 2) continue
            val secondary = if (rowsFirst) clusterLabels(members.map { xs[it] }, frame.w * CLUSTER_GAP, SIDES)
            else clusterLabels(members.map { ys[it] }, frame.h * CLUSTER_GAP, HEIGHTS)
            if (secondary == null || secondary.distinct().size != members.size) return null
            members.forEachIndexed { k, i -> out[i] = if (rowsFirst) out[i].first to secondary[k] else secondary[k] to out[i].second }
        }
        return out
    }

    /** Makes sure no two strokes share a description (falls back to order within a kind). */
    private fun ensureUnique(out: List<String>, names: List<String>): List<String> {
        val dupes = out.indices.groupBy { out[it] }.values.filter { it.size >= 2 }
        if (dupes.isEmpty()) return out
        val fixed = out.toMutableList()
        for (d in dupes) d.forEachIndexed { k, i -> fixed[i] = Words().apply { ordinal = ordinal(k + 1) }.render(names[i]) }
        return fixed
    }

    private val SIDES = listOf(listOf("left", "right"), listOf("left", MIDDLE, "right"))
    private val HEIGHTS = listOf(listOf("upper", "lower"), listOf("top", MIDDLE, "bottom"))

    /** Clusters 1-D positions separated by gaps of at least [gap]; null unless there are 2 or 3 clusters. */
    private fun clusterLabels(values: List<Double>, gap: Double, words: List<List<String>>): List<String>? {
        val cluster = levels(values, gap)
        val count = cluster.max() + 1
        if (count !in 2..3) return null
        val labels = words[count - 2]
        return cluster.map { labels[it] }
    }

    /** Groups positions: neighbours closer than [gap] share a level. Level 0 is the lowest value. */
    private fun levels(values: List<Double>, gap: Double = LEVEL_GAP): List<Int> {
        val order = values.indices.sortedBy { values[it] }
        val level = IntArray(values.size)
        var l = 0
        for (k in 1 until order.size) {
            if (values[order[k]] - values[order[k - 1]] >= gap) l++
            level[order[k]] = l
        }
        return level.toList()
    }

    /** The straight horizontal and vertical stretches along one stroke, turns included. */
    private fun runsOf(stroke: Int, pts: List<Point>): List<Run> {
        val length = Geometry.pathLength(pts)
        if (length < MIN_RUN) return emptyList()
        val n = length.toInt().coerceIn(8, 400) + 1
        val r = Geometry.resample(pts, n)
        val step = length / (n - 1)
        val out = ArrayList<Run>()
        var kind = 0
        var from = 0
        fun close(to: Int) {
            if (kind != 0 && (to - from) * step >= MIN_RUN) out += Run(stroke, kind == 1, Geometry.bounds(r.subList(from, to + 1)))
        }
        for (j in 0 until n - 1) {
            // Direction over a few units either side, so a wobble or a rounded corner doesn't split a stretch.
            val a = r[max(0, j - 2)]
            val b = r[min(n - 1, j + 3)]
            val dx = abs(b.x - a.x)
            val dy = abs(b.y - a.y)
            val k = when {
                dy <= AXIS_SLOPE * dx -> 1
                dx <= AXIS_SLOPE * dy -> 2
                else -> 0
            }
            if (k != kind) {
                close(j)
                kind = k
                from = j
            }
        }
        close(n - 1)
        return out
    }

    private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")

    private fun ordinal(n: Int) = ORDINALS.getOrElse(n - 1) { "${n}th" }
}
