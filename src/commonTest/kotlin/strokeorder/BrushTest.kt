package strokeorder

import strokeorder.ink.BrushModel
import strokeorder.ink.Ending
import strokeorder.ink.InputPoint
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushTest {
    /** A straight left-to-right stroke of [length] units drawn at [speed] units per ms. */
    private fun sweep(length: Double, speed: Double, n: Int = 40, endSpeed: Double = speed) =
        (0..n).map { i ->
            val x = 10 + length * i / n
            // time grows so the last few points move at endSpeed
            val step = length / n
            InputPoint(x, 50.0, 0.0).copy(t = (0 until i).sumOf { j -> step / (if (j >= n - 4) endSpeed else speed) })
        }

    @Test
    fun fasterStrokesAreThinner() {
        fun meanWidth(speed: Double) = BrushModel.live(sweep(80.0, speed)).map { it.w }.average()
        assertTrue(meanWidth(0.05) > meanWidth(0.2))
        assertTrue(meanWidth(0.2) > meanWidth(0.6))
        assertTrue(BrushModel.speedFactor(0.0) > BrushModel.speedFactor(1.0))
    }

    @Test
    fun penPressureMakesTheLineFuller() {
        val light = sweep(80.0, 0.15).map { it.copy(pressure = 0.1) }
        val heavy = sweep(80.0, 0.15).map { it.copy(pressure = 0.9) }
        assertTrue(BrushModel.live(heavy).map { it.w }.average() > BrushModel.live(light).map { it.w }.average())
    }

    @Test
    fun aFlickEndsInATaperedSweepAndAHoldEndsInAStop() {
        val flick = BrushModel.finish(sweep(80.0, 0.15, endSpeed = 0.9), seed = 1)
        val hold = BrushModel.finish(sweep(80.0, 0.15, endSpeed = 0.04), seed = 1)
        assertEquals(Ending.SWEEP, flick.ending)
        assertEquals(Ending.STOP, hold.ending)
        assertTrue(flick.samples.last().w < BrushModel.BASE_WIDTH * 0.25, "sweep tapers to a point")
        assertTrue(hold.samples.last().w > BrushModel.BASE_WIDTH * 0.5, "stop keeps its body")
        assertTrue(flick.dryness > hold.dryness)
    }

    @Test
    fun samplesAreDenseEnoughToLookContinuous() {
        // Even from sparse input, neighbouring discs overlap by well over half their width.
        val s = BrushModel.finish(sweep(80.0, 0.2, n = 6), seed = 2).samples
        for (i in 1 until s.size) {
            val gap = hypot(s[i].x - s[i - 1].x, s[i].y - s[i - 1].y)
            assertTrue(gap <= 0.35 * minOf(s[i].w, s[i - 1].w) || gap <= 0.3, "gap $gap at $i")
        }
    }

    @Test
    fun hugeInputIsCapped() {
        val scribble = (0 until 50_000).map { InputPoint((it % 100).toDouble(), (it / 100 % 100).toDouble(), it.toDouble()) }
        val s = BrushModel.finish(scribble, seed = 3).samples
        assertTrue(s.size <= BrushModel.MAX_SAMPLES + 1)
    }

    @Test
    fun aTapStillLeavesAMark() {
        assertEquals(1, BrushModel.live(listOf(InputPoint(5.0, 5.0, 0.0))).size)
        assertTrue(BrushModel.live(emptyList()).isEmpty())
    }

    @Test
    fun referenceStrokesGetCalligraphicEndings() {
        val rightSweep = Fixtures.HITO.strokes[1]
        val horizontal = Fixtures.ICHI.strokes[0]
        assertEquals(Ending.SWEEP, BrushModel.finish(BrushModel.fromReference(rightSweep.points, rightSweep.type), 1).ending)
        assertEquals(Ending.STOP, BrushModel.finish(BrushModel.fromReference(horizontal.points, horizontal.type), 1).ending)
    }
}
