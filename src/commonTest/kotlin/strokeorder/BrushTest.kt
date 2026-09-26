package strokeorder

import strokeorder.ink.BrushModel
import strokeorder.ink.Ending
import strokeorder.ink.InputPoint
import strokeorder.ink.Nib
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

    @Test
    fun pressureFillsTheBrushOutButCannotBalloonIt() {
        val slow = sweep(80.0, 0.01)
        val heavy = BrushModel.live(slow.map { it.copy(pressure = 1.0) }).map { it.w }
        val light = BrushModel.live(slow.map { it.copy(pressure = 0.0) }).map { it.w }
        // Never more than the cap (plus the small swell where the brush presses in).
        assertTrue(heavy.max() <= BrushModel.BASE_WIDTH * BrushModel.MAX_FACTOR * 1.1 + 1e-9, "max ${heavy.max()}")
        assertTrue(heavy.average() / light.average() < 1.35, "heavy/light ${heavy.average() / light.average()}")
    }

    @Test
    fun aSweepEndsInASharpTipAndAHookFlicksShort() {
        val sweep = BrushModel.finish(sweep(80.0, 0.2), seed = 4, ending = Ending.SWEEP)
        assertTrue(sweep.samples.last().w < BrushModel.BASE_WIDTH * 0.05, "tip ${sweep.samples.last().w}")
        val hook = BrushModel.finish(sweep(80.0, 0.2), seed = 4, ending = Ending.HOOK)
        // A hook tapers only over its last few units; a sweep over much more.
        fun taperStartsAt(s: List<strokeorder.ink.BrushSample>) = s.indexOfLast { it.w > BrushModel.BASE_WIDTH * 0.6 }.toDouble() / s.size
        assertTrue(taperStartsAt(hook.samples) > taperStartsAt(sweep.samples))
        assertTrue(hook.samples.last().w < BrushModel.BASE_WIDTH * 0.1)
    }

    @Test
    fun theBrushRunsDryOnlyWhereItMovesFast() {
        val slow = BrushModel.finish(sweep(80.0, 0.12, endSpeed = 0.05), seed = 5)
        assertEquals(0.0, slow.dryness, "a slow, held stroke stays wet")
        val fast = BrushModel.finish(sweep(80.0, 0.8), seed = 5)
        assertTrue(fast.dryness > 0.5, "a fast stroke runs dry: ${fast.dryness}")
        // …and drier towards its end than at its start.
        val d = fast.samples.map { it.dry }
        assertTrue(d.takeLast(d.size / 4).average() > d.take(d.size / 4).average())
    }

    @Test
    fun theAngledNibMakesThickAndThin() {
        fun width(dx: Double, dy: Double) = Nib.markWidth(1.0, dx, dy)
        assertTrue(width(0.0, 1.0) > width(1.0, 0.0), "verticals are broader than horizontals")
        assertTrue(width(1.0, 0.8) > width(-0.6, 1.0), "the right-falling sweep is broader than the left-falling one")
        assertTrue(width(1.0, 0.0) >= Nib.RATIO && width(0.0, 1.0) <= 1.0)
    }

    @Test
    fun eachStrokeTypeGetsItsEnding() {
        assertEquals(Ending.SWEEP, BrushModel.endingFor("㇒"))
        assertEquals(Ending.SWEEP, BrushModel.endingFor("㇏"))
        assertEquals(Ending.STOP, BrushModel.endingFor("㇐"))
        assertEquals(Ending.STOP, BrushModel.endingFor("㇔"))
        assertEquals(Ending.STOP, BrushModel.endingFor("㇕a"))
        assertEquals(Ending.HOOK, BrushModel.endingFor("㇚"))
        assertEquals(Ending.HOOK, BrushModel.endingFor("㇆a"))
        // A recognised stroke's ending wins over how fast the pen left the paper.
        assertEquals(Ending.STOP, BrushModel.finish(sweep(80.0, 0.15, endSpeed = 0.9), seed = 1, ending = Ending.STOP).ending)
    }
}
