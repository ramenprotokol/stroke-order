package strokeorder.engine

/**
 * Tuning for stroke comparison. Distances are in KanjiVG units (the character box is
 * 109 units wide); shape distances are in "fractions of the stroke's own size".
 */
data class MatchConfig(
    /** Points per stroke after resampling. */
    val samples: Int = 32,
    /** Position tolerance: base + perLength × (reference stroke length). */
    val locBase: Double = 9.0,
    val locPerLength: Double = 0.12,
    /** Extra position tolerance before any stroke has been accepted (nothing to align to yet). */
    val firstStrokeSlack: Double = 9.0,
    /** Largest normalised discrete Fréchet distance that still counts as the same shape. */
    val shapeTol: Double = 0.34,
    /** Largest mean difference (radians) between the heading of matching stretches of stroke. */
    val headingTol: Double = 0.62,
    /** How many stretches the heading signature compares (fewer for short strokes). */
    val headingSegments: Int = 8,
    /** Shapes are never normalised as if they were smaller than this (keeps dots stable). */
    val minExtent: Double = 14.0,
    /** Reference strokes shorter than this (dots) are not judged on direction. */
    val minDirectionalLength: Double = 13.0,
    /** User strokes shorter than this are treated as taps, not strokes. */
    val minUserLength: Double = 1.8,
    /** The expected stroke wins if its score is within this margin of the best match. */
    val preferExpectedMargin: Double = 0.45,
    /** A wider margin for the first stroke, which has nothing to be aligned against yet. */
    val firstStrokeMargin: Double = 0.6,
)

/**
 * How well a user stroke fits one reference stroke in one orientation, on three
 * measures: where it is ([loc]), its outline ([shape]) and which way each stretch of it
 * heads ([heading]). Each is paired with its tolerance.
 */
data class Fit(
    val loc: Double,
    val shape: Double,
    val heading: Double,
    val locTol: Double,
    val shapeTol: Double,
    val headingTol: Double,
) {
    val passes: Boolean get() = loc <= locTol && shape <= shapeTol && heading <= headingTol
    /** Lower is better; a passing fit scores at most 3. */
    val score: Double get() = loc / locTol + shape / shapeTol + heading / headingTol
}

/** A user stroke compared with a reference stroke drawn forwards and backwards. */
data class Comparison(val forward: Fit, val backward: Fit, val directionMatters: Boolean) {
    /** True when the stroke fits the reference better when read backwards. */
    val looksReversed: Boolean get() = directionMatters && backward.score < forward.score
    val best: Fit get() = if (directionMatters) (if (looksReversed) backward else forward) else minOf(forward, backward, compareBy { it.score })
    val passes: Boolean get() = best.passes
    /** Drawn the wrong way round: the backward reading fits, the forward one does not. */
    val wrongDirection: Boolean get() = looksReversed && backward.passes && !forward.passes
}

/**
 * A reference stroke prepared once for repeated comparisons. [directional] is false for
 * strokes whose direction is not judged (dots, and anything very short).
 */
class PreparedStroke(points: List<Point>, config: MatchConfig, directional: Boolean = true) {
    val length = Geometry.pathLength(points)
    val resampled: List<Point> = Geometry.resample(points, config.samples)
    val reversed: List<Point> = resampled.asReversed().toList()
    val shape: List<Point> = Geometry.normalize(resampled, config.minExtent)
    val shapeReversed: List<Point> = shape.asReversed().toList()
    /** Short strokes are compared over fewer, longer stretches so hand jitter can't dominate. */
    val headingSegments = (length / 8.0).toInt().coerceIn(3, config.headingSegments)
    val headings: DoubleArray = Geometry.headings(resampled, headingSegments)
    val headingsReversed: DoubleArray = Geometry.headings(reversed, headingSegments)
    val directionMatters = directional && length >= config.minDirectionalLength
}

/**
 * Compares strokes by position (mean distance between evenly resampled points), by
 * shape (discrete Fréchet distance after normalising position and size) and by heading
 * (the direction of travel along each stretch), in both directions. Position tells
 * apart the three identical horizontals of 三; shape and heading tell a turn or a V from
 * a straight line; the two directions tell a stroke drawn backwards.
 */
class StrokeMatcher(val config: MatchConfig = MatchConfig()) {
    fun prepare(points: List<Point>, directional: Boolean = true) = PreparedStroke(points, config, directional)

    fun resample(points: List<Point>) = Geometry.resample(points, config.samples)

    /** [user] must already be resampled with [resample]. */
    fun compare(user: List<Point>, ref: PreparedStroke, slack: Double = 0.0): Comparison {
        require(user.size == config.samples) { "user stroke must be resampled to ${config.samples} points" }
        val userShape = Geometry.normalize(user, config.minExtent)
        val userHeadings = Geometry.headings(user, ref.headingSegments)
        val locTol = config.locBase + config.locPerLength * ref.length + slack
        val fwd = Fit(
            Geometry.meanDistance(user, ref.resampled),
            Geometry.discreteFrechet(userShape, ref.shape),
            Geometry.meanAngleDifference(userHeadings, ref.headings),
            locTol, config.shapeTol, config.headingTol,
        )
        val bwd = Fit(
            Geometry.meanDistance(user, ref.reversed),
            Geometry.discreteFrechet(userShape, ref.shapeReversed),
            Geometry.meanAngleDifference(userHeadings, ref.headingsReversed),
            locTol, config.shapeTol, config.headingTol,
        )
        return Comparison(fwd, bwd, ref.directionMatters)
    }
}
