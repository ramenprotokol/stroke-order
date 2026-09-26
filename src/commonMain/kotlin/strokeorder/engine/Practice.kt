package strokeorder.engine

import strokeorder.model.Kanji

/** What happened to one submitted stroke. */
sealed interface Verdict {
    /** The expected stroke, drawn the right way. [complete] is true for the last one. */
    data class Accepted(val index: Int, val complete: Boolean) : Verdict

    /** The expected stroke, drawn backwards. */
    data class WrongDirection(val index: Int) : Verdict

    /** A later stroke of the character, written before the one that comes next. */
    data class OutOfOrder(val drawn: Int, val expected: Int) : Verdict

    /** A stroke that has already been written. */
    data class AlreadyWritten(val drawn: Int, val expected: Int) : Verdict

    /** Nothing in the character matches well enough. */
    data class Unrecognized(val expected: Int) : Verdict

    /** A tap or a scribble too small to judge. */
    data object TooShort : Verdict

    /** Every stroke is already written. */
    data object AlreadyComplete : Verdict
}

/** The state of one reference stroke on the sheet. */
sealed interface StrokeState {
    data object Pending : StrokeState
    data object Next : StrokeState
    data class Written(val attempts: Int) : StrokeState
}

/** The state of the whole sheet. */
sealed interface Progress {
    data class Writing(val next: Int, val total: Int) : Progress
    data class Complete(val total: Int, val slips: Int) : Progress
}

/**
 * The stroke-order state machine for one character. Feed it each stroke the writer
 * draws (in the 109 × 109 KanjiVG frame); it decides which reference stroke that was,
 * whether it was the next one, and whether it went the right way.
 */
class Practice(val kanji: Kanji, val matcher: StrokeMatcher = StrokeMatcher()) {
    private val config get() = matcher.config
    // Dots (㇔) are too short for their direction to be read reliably from a finger.
    private val refs: List<PreparedStroke> = kanji.strokes.map {
        val dot = it.type.startsWith("㇔")
        matcher.prepare(it.points, directional = !dot, dot = dot)
    }

    /**
     * The kind of each stroke ("horizontal", "vertical"…): strokes of one kind are told
     * apart by position. Dots and other short marks are one kind: a small dab carries
     * little but where it lands.
     */
    private val kinds: List<String> = kanji.strokes.indices.map { if (refs[it].mark) MARK else Describe.typeName(kanji.strokes[it].type) }

    private class Written(val index: Int, val userResampled: List<Point>, val attempts: Int)

    private val written = ArrayList<Written>()
    private var attemptsOnNext = 0

    /** Strokes that were rejected before being written correctly (for the closing note). */
    var slips: Int = 0
        private set

    var alignment: Similarity = Similarity.IDENTITY
        private set

    val total: Int get() = refs.size
    val next: Int get() = written.size
    val isComplete: Boolean get() = written.size == refs.size

    val progress: Progress
        get() = if (isComplete) Progress.Complete(total, slips) else Progress.Writing(next, total)

    fun state(index: Int): StrokeState = when {
        index < written.size -> StrokeState.Written(written[index].attempts)
        index == written.size -> StrokeState.Next
        else -> StrokeState.Pending
    }

    fun submit(points: List<Point>): Verdict {
        if (isComplete) return Verdict.AlreadyComplete
        if (points.isEmpty()) return Verdict.TooShort
        val box = Geometry.bounds(points)
        if (Geometry.pathLength(points) < config.minUserLength || box.diagonal < config.minUserLength) {
            return Verdict.TooShort
        }
        val raw = matcher.resample(points)
        val aligned = alignment.apply(raw)
        val comparisons = refs.map { matcher.compare(aligned, it, slack(it)) }

        val k = next
        val candidates = comparisons.indices.filter { comparisons[it].passes }
        attemptsOnNext++
        if (candidates.isEmpty()) return reject(Verdict.Unrecognized(k))

        val chosen = choose(k, candidates, comparisons)

        return when {
            chosen == k && comparisons[k].wrongDirection -> reject(Verdict.WrongDirection(k))
            chosen == k -> accept(k, raw)
            chosen < k -> reject(Verdict.AlreadyWritten(chosen, k))
            else -> reject(Verdict.OutOfOrder(chosen, k))
        }
    }

    /**
     * Extra position tolerance for [ref]. Before the first stroke nothing can be aligned,
     * and until the writer's scale is known a stroke may be off in proportion to how far
     * it lies from what is written so far (from the middle of the box, for the first).
     */
    private fun slack(ref: PreparedStroke): Double = when {
        written.isEmpty() -> config.firstStrokeSlack + config.scaleSlack * ref.centroid.dist(BOX_CENTRE)
        !alignment.scaleKnown -> config.scaleSlack * ref.centroid.dist(anchor)
        else -> 0.0
    }

    /** The centre of the reference strokes written so far. */
    private var anchor: Point = BOX_CENTRE

    /**
     * Which passing reference stroke the writer meant. Look-alikes of one kind (三's three
     * horizontals, 学's dots and short marks) differ mainly in where they sit, so within a
     * kind the nearest wins. The expected stroke then wins over strokes of other kinds unless one
     * of them fits clearly better by overall score.
     *
     * Before the first stroke there is nothing to align the writing to, so position is
     * weaker evidence: the expected stroke keeps the benefit of the doubt unless the
     * stroke lies outside where the expected one would ordinarily be accepted and a
     * look-alike is nearer.
     */
    private fun choose(k: Int, candidates: List<Int>, comparisons: List<Comparison>): Int {
        val first = written.isEmpty()
        fun dist(i: Int) = comparisons[i].best.loc
        val perKind = candidates.groupBy { kinds[it] }.values.map { same ->
            val nearest = same.minBy { dist(it) }
            when {
                k !in same || nearest == k -> nearest
                first -> {
                    // Clear evidence: not even where stroke k would ordinarily be accepted, and nearer a look-alike.
                    val ordinary = config.locBase + config.locPerLength * refs[k].length
                    if (dist(k) > ordinary && dist(nearest) < dist(k) - config.nearerBy) nearest else k
                }
                else -> if (dist(nearest) < dist(k) - config.nearerBy) nearest else k
            }
        }
        val best = perKind.minBy { comparisons[it].best.score }
        val margin = if (first) config.firstStrokeMargin else config.preferExpectedMargin
        return if (k in perKind && comparisons[k].best.score <= comparisons[best].best.score + margin) k else best
    }

    private fun reject(v: Verdict): Verdict {
        slips++
        return v
    }

    private fun accept(k: Int, userResampled: List<Point>): Verdict {
        written.add(Written(k, userResampled, attemptsOnNext))
        attemptsOnNext = 0
        refit()
        return Verdict.Accepted(k, complete = isComplete)
    }

    private fun refit() {
        val user = written.flatMap { it.userResampled }
        val ref = written.flatMap { refs[it.index].resampled }
        alignment = Similarity.fit(user, ref)
        anchor = if (ref.isEmpty()) BOX_CENTRE else Geometry.centroid(ref)
    }

    private companion object {
        /** The middle of KanjiVG's 109 × 109 box. */
        val BOX_CENTRE = Point(54.5, 54.5)
        const val MARK = "mark"
    }

    /** Removes the last written stroke. Returns its index, or null when nothing is written. */
    fun undo(): Int? {
        if (written.isEmpty()) return null
        val removed = written.removeAt(written.size - 1)
        attemptsOnNext = 0
        refit()
        return removed.index
    }

    fun reset() {
        written.clear()
        attemptsOnNext = 0
        slips = 0
        alignment = Similarity.IDENTITY
        anchor = BOX_CENTRE
    }
}
