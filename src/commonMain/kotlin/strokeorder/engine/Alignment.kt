package strokeorder.engine

import kotlin.math.sqrt

/**
 * A uniform scale plus translation that maps the writer's coordinates onto the
 * reference frame. People rarely write exactly on top of the model: this lets later
 * strokes be judged relative to the ones already written.
 */
data class Similarity(val scale: Double, val tx: Double, val ty: Double) {
    fun apply(p: Point) = Point(scale * p.x + tx, scale * p.y + ty)
    fun apply(pts: List<Point>) = pts.map { apply(it) }

    companion object {
        val IDENTITY = Similarity(1.0, 0.0, 0.0)

        const val MIN_SCALE = 0.7
        const val MAX_SCALE = 1.45
        /** Below this RMS spread (units) the scale is not trusted and stays 1. */
        const val MIN_SPREAD = 6.0

        /**
         * Least-squares fit of `ref ≈ s·user + t` over matched point pairs. The scale is
         * clamped to [MIN_SCALE, MAX_SCALE] and only estimated when the points spread out
         * enough to measure it.
         */
        fun fit(user: List<Point>, ref: List<Point>): Similarity {
            require(user.size == ref.size) { "fit needs matched point pairs" }
            if (user.isEmpty()) return IDENTITY
            val mu = Geometry.centroid(user)
            val mr = Geometry.centroid(ref)
            var varU = 0.0
            var cov = 0.0
            for (i in user.indices) {
                val du = user[i] - mu
                val dr = ref[i] - mr
                varU += du.x * du.x + du.y * du.y
                cov += du.x * dr.x + du.y * dr.y
            }
            varU /= user.size
            cov /= user.size
            val s = if (sqrt(varU) >= MIN_SPREAD) (cov / varU).coerceIn(MIN_SCALE, MAX_SCALE) else 1.0
            return Similarity(s, mr.x - s * mu.x, mr.y - s * mu.y)
        }
    }
}
