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
    /**
     * While the writer's scale is still unknown (before any stroke, or after only a tiny
     * one such as 字's first tick), a stroke far from what has been written may be off by
     * a share of that distance: this much extra tolerance per unit of distance.
     */
    val scaleSlack: Double = 0.25,
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
    /**
     * Reference strokes shorter than this, and every dot (㇔), are "marks": a dab of the
     * brush. They are judged on where they land, a rough heading and not being far too
     * long, but not on their exact outline, because a finger's dab is often much shorter
     * than KanjiVG's drawn dot.
     */
    val markLength: Double = 18.0,
    /** Heading tolerance (radians) for marks, compared over the whole mark. */
    val markHeadingTol: Double = 1.0,
    /** Marks smaller than this (units, corner to corner) are too short for their heading to mean anything. */
    val markMinHeadingLength: Double = 5.0,
    /** User strokes shorter than this are treated as taps, not strokes. */
    val minUserLength: Double = 1.8,
    /**
     * The expected stroke wins over a stroke of a different kind if its score is within
     * this margin of the best match. (Strokes of the same kind are told apart by position.)
     */
    val preferExpectedMargin: Double = 0.45,
    /** A wider margin for the first stroke, which has nothing to be aligned against yet. */
    val firstStrokeMargin: Double = 0.6,
    /**
     * A same-kind look-alike must be this much nearer (units) than the expected stroke to
     * be taken instead. For the first stroke it must also be that the expected stroke is
     * outside its ordinary position tolerance (without the first-stroke slack): until
     * then, the expected stroke keeps the benefit of the doubt.
     */
    val nearerBy: Double = 1.0,
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
    /**
     * The better reading: one that passes beats one that fails, whatever their scores
     * (a backward reading can score lower overall yet fail one measure outright);
     * otherwise the lower score.
     */
    val best: Fit get() = when {
        forward.passes != backward.passes -> if (forward.passes) forward else backward
        backward.score < forward.score -> backward
        else -> forward
    }
    /** True when the stroke is read backwards (only for strokes whose direction is judged). */
    val looksReversed: Boolean get() = directionMatters && best === backward
    val passes: Boolean get() = best.passes
    /** Drawn the wrong way round: the backward reading fits, the forward one does not. */
    val wrongDirection: Boolean get() = looksReversed && backward.passes && !forward.passes
}

/**
 * A reference stroke prepared once for repeated comparisons. [directional] is false for
 * strokes whose direction is not judged (dots, and anything very short).
 */
class PreparedStroke(points: List<Point>, config: MatchConfig, directional: Boolean = true, dot: Boolean = false) {
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
    /** A dot or other very short mark (see [MatchConfig.markLength]). */
    val mark: Boolean = dot || length < config.markLength
    val centroid: Point = Geometry.centroid(resampled)
    val diagonal: Double = Geometry.bounds(resampled).diagonal
}

/**
 * Compares strokes by position (mean distance between evenly resampled points), by
 * shape (discrete Fréchet distance after normalising position and size) and by heading
 * (the direction of travel along each stretch), in both directions. Position tells
 * apart the three identical horizontals of 三; shape and heading tell a turn or a V from
 * a straight line; the two directions tell a stroke drawn backwards.
 */
class StrokeMatcher(val config: MatchConfig = MatchConfig()) {
    fun prepare(points: List<Point>, directional: Boolean = true, dot: Boolean = false) = PreparedStroke(points, config, directional, dot)

    fun resample(points: List<Point>) = Geometry.resample(points, config.samples)

    /** [user] must already be resampled with [resample]. */
    fun compare(user: List<Point>, ref: PreparedStroke, slack: Double = 0.0): Comparison {
        require(user.size == config.samples) { "user stroke must be resampled to ${config.samples} points" }
        if (ref.mark) return compareMark(user, ref, slack)
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

    /**
     * A dot or short mark. Position is where the dab lands (centroid to centroid), so a
     * short dab anywhere on a long KanjiVG dot counts. Shape compares outlines at the
     * dab's own size (the reference is shrunk to it, never the dab blown up, so hand
     * jitter on a tiny dab isn't magnified), plus a cap on how much longer than the
     * reference the mark may be. Heading is one overall direction with a wide tolerance;
     * a dab too short to have one isn't judged on it, unless the mark's direction counts.
     */
    private fun compareMark(user: List<Point>, ref: PreparedStroke, slack: Double): Comparison {
        // Sizes by extent, not path length: jitter along a tiny dab inflates its length.
        val userDiag = Geometry.bounds(user).diagonal
        val loc = Geometry.centroid(user).dist(ref.centroid)
        val locTol = config.locBase + config.locPerLength * ref.length + slack
        val scale = 1.0 / maxOf(userDiag, config.minExtent)
        val shrink = if (ref.diagonal > userDiag && ref.diagonal > 0.0) userDiag / ref.diagonal else 1.0
        val refShape = ref.resampled.map { (it - ref.centroid) * (shrink * scale) }
        val userCentroid = Geometry.centroid(user)
        val userShape = user.map { (it - userCentroid) * scale }
        // A mark more than about twice its reference's size is a line, not a dab.
        val tooLong = config.shapeTol * userDiag / (2.0 * ref.diagonal + 8.0)
        // A dab too small to have a heading isn't judged on one, unless its direction is judged.
        val judgeHeading = ref.directionMatters || userDiag >= config.markMinHeadingLength
        val userHeading = Geometry.headings(user, 1)
        fun fit(refPts: List<Point>, refHeading: DoubleArray) = Fit(
            loc,
            maxOf(Geometry.discreteFrechet(userShape, refPts), tooLong),
            if (judgeHeading) Geometry.meanAngleDifference(userHeading, refHeading) else 0.0,
            locTol, config.shapeTol, config.markHeadingTol,
        )
        val fwd = fit(refShape, Geometry.headings(ref.resampled, 1))
        val bwd = fit(refShape.asReversed(), Geometry.headings(ref.reversed, 1))
        return Comparison(fwd, bwd, ref.directionMatters)
    }
}
