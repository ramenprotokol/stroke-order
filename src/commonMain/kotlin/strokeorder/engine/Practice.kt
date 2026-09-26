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
    private val refs: List<PreparedStroke> = kanji.strokes.map { matcher.prepare(it.points, directional = !it.type.startsWith("㇔")) }

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
        val slack = if (written.isEmpty()) config.firstStrokeSlack else 0.0
        val comparisons = refs.map { matcher.compare(aligned, it, slack) }

        val k = next
        val candidates = comparisons.indices.filter { comparisons[it].passes }
        attemptsOnNext++
        if (candidates.isEmpty()) return reject(Verdict.Unrecognized(k))

        val best = candidates.minBy { comparisons[it].best.score }
        val margin = if (written.isEmpty()) config.firstStrokeMargin else config.preferExpectedMargin
        val chosen = if (k in candidates && comparisons[k].best.score <= comparisons[best].best.score + margin) k else best

        return when {
            chosen == k && comparisons[k].wrongDirection -> reject(Verdict.WrongDirection(k))
            chosen == k -> accept(k, raw)
            chosen < k -> reject(Verdict.AlreadyWritten(chosen, k))
            else -> reject(Verdict.OutOfOrder(chosen, k))
        }
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
    }
}
