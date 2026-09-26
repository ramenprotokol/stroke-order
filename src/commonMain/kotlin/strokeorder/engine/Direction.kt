package strokeorder.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Compass direction of a stroke from where it starts to where it ends, in screen
 * terms (y grows downwards, so SOUTH means "down the page").
 */
enum class Compass(val phrase: String) {
    EAST("left to right"),
    SOUTH_EAST("down to the right"),
    SOUTH("top to bottom"),
    SOUTH_WEST("down to the left"),
    WEST("right to left"),
    NORTH_WEST("up to the left"),
    NORTH("bottom to top"),
    NORTH_EAST("up to the right");

    val opposite: Compass get() = entries[(ordinal + 4) % 8]

    companion object {
        /** Direction of travel from [from] to [to]; null when the two points coincide. */
        fun between(from: Point, to: Point): Compass? {
            val dx = to.x - from.x
            val dy = to.y - from.y
            if (dx == 0.0 && dy == 0.0) return null
            // atan2 with y down: 0 = east, +90° = south.
            val sector = ((atan2(dy, dx) / (PI / 4)).roundToInt() + 8) % 8
            return entries[sector]
        }

        /** Overall direction of a polyline (first point to last point). */
        fun of(stroke: List<Point>): Compass? =
            if (stroke.size < 2) null else between(stroke.first(), stroke.last())
    }
}
