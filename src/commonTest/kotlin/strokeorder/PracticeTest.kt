package strokeorder

import strokeorder.engine.Feedback
import strokeorder.engine.Practice
import strokeorder.engine.Progress
import strokeorder.engine.StrokeState
import strokeorder.engine.Verdict
import strokeorder.model.Kanji
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PracticeTest {
    private fun writeAll(k: Kanji, strokes: List<List<strokeorder.engine.Point>>): Pair<Practice, List<Verdict>> {
        val p = Practice(k)
        return p to strokes.map { p.submit(it) }
    }

    @Test
    fun referenceStrokesInOrderCompleteEveryCharacter() {
        for (k in Fixtures.ALL) {
            val (p, verdicts) = writeAll(k, Fixtures.strokes(k))
            verdicts.forEachIndexed { i, v -> assertEquals(Verdict.Accepted(i, complete = i == k.strokeCount - 1), v, "${k.char} stroke ${i + 1}") }
            assertEquals(Progress.Complete(k.strokeCount, slips = 0), p.progress)
            assertEquals(Verdict.AlreadyComplete, p.submit(Fixtures.strokes(k)[0]))
        }
    }

    @Test
    fun sloppyButCorrectWritingStillCompletes() {
        for (k in Fixtures.ALL) for (seed in 1..5) {
            val (p, verdicts) = writeAll(k, Perturb.sloppy(Fixtures.strokes(k), seed))
            assertTrue(p.isComplete, "${k.char} seed $seed: $verdicts")
        }
    }

    @Test
    fun writingTheSecondStrokeFirstIsOutOfOrder() {
        for (k in Fixtures.ALL.filter { it.strokeCount >= 2 }) {
            val p = Practice(k)
            val v = p.submit(Fixtures.strokes(k)[1])
            assertEquals(Verdict.OutOfOrder(drawn = 1, expected = 0), v, k.char)
            assertEquals(StrokeState.Next, p.state(0))
        }
    }

    @Test
    fun outOfOrderFeedbackNamesBothStrokes() {
        // 右 starts with the sweep, not the horizontal (左 is the other way round).
        val p = Practice(Fixtures.MIGI)
        val v = p.submit(Fixtures.strokes(Fixtures.MIGI)[1])
        assertEquals("That's stroke 2 — stroke 1 comes first (the left sweep).", Feedback.forVerdict(Fixtures.MIGI, v))
        // 三: writing the bottom line after the top one skips the middle.
        val san = Practice(Fixtures.SAN)
        san.submit(Fixtures.strokes(Fixtures.SAN)[0])
        val skip = san.submit(Fixtures.strokes(Fixtures.SAN)[2])
        assertEquals(Verdict.OutOfOrder(2, 1), skip)
        assertEquals("That's stroke 3 — stroke 2 comes first (the horizontal across the middle).", Feedback.forVerdict(Fixtures.SAN, skip))
    }

    @Test
    fun aReversedStrokeIsCaughtAndNotAccepted() {
        val p = Practice(Fixtures.JUU)
        val v = p.submit(Fixtures.strokes(Fixtures.JUU)[0].reversed())
        assertEquals(Verdict.WrongDirection(0), v)
        assertEquals("Right stroke, other way round — stroke 1 runs left to right.", Feedback.forVerdict(Fixtures.JUU, v))
        assertEquals(0, p.next)
        assertIs<Verdict.Accepted>(p.submit(Fixtures.strokes(Fixtures.JUU)[0]))
        assertEquals(Verdict.WrongDirection(1), p.submit(Fixtures.strokes(Fixtures.JUU)[1].reversed()))
        assertEquals(Progress.Writing(next = 1, total = 2), p.progress)
    }

    @Test
    fun rewritingAFinishedStrokeIsReported() {
        val p = Practice(Fixtures.SAN)
        p.submit(Fixtures.strokes(Fixtures.SAN)[0])
        assertEquals(Verdict.AlreadyWritten(drawn = 0, expected = 1), p.submit(Fixtures.strokes(Fixtures.SAN)[0]))
    }

    @Test
    fun clearlyWrongShapesAndTapsAreRejected() {
        val p = Practice(Fixtures.ICHI)
        assertEquals(Verdict.Unrecognized(0), p.submit(circle(54.0, 52.0, 25.0)))
        assertEquals(Verdict.Unrecognized(0), p.submit(line(54.0, 12.0, 54.0, 96.0)))
        assertEquals(Verdict.TooShort, p.submit(listOf(strokeorder.engine.Point(40.0, 40.0))))
        assertEquals(Verdict.TooShort, p.submit(line(40.0, 40.0, 40.5, 40.5)))
        assertEquals(2, p.slips)
        val kuchi = Practice(Fixtures.KUCHI)
        kuchi.submit(Fixtures.strokes(Fixtures.KUCHI)[0])
        val turn = Fixtures.strokes(Fixtures.KUCHI)[1]
        val v = kuchi.submit(turn.take(turn.size / 2))
        assertEquals(Verdict.Unrecognized(1), v)
        assertTrue(Feedback.forVerdict(Fixtures.KUCHI, v).contains("don't lift the brush"))
    }

    @Test
    fun undoStepsBackOneStroke() {
        val p = Practice(Fixtures.SAN)
        p.submit(Fixtures.strokes(Fixtures.SAN)[0])
        p.submit(Fixtures.strokes(Fixtures.SAN)[1])
        assertEquals(1, p.undo())
        assertEquals(1, p.next)
        assertIs<StrokeState.Written>(p.state(0))
        assertEquals(StrokeState.Next, p.state(1))
        assertEquals(StrokeState.Pending, p.state(2))
        p.reset()
        assertEquals(0, p.next)
        assertEquals(null, p.undo())
    }

    @Test
    fun strokesDescribeThemselves() {
        val names = Fixtures.SAN.strokes.indices.map { strokeorder.engine.Describe.stroke(Fixtures.SAN, it) }
        assertEquals(listOf("the top horizontal", "the horizontal across the middle", "the bottom horizontal"), names)
        assertEquals("the dot", strokeorder.engine.Describe.stroke(Fixtures.HI, 0))
        assertEquals("the short left sweep", strokeorder.engine.Describe.stroke(Fixtures.HI, 1))
        assertEquals("the long left sweep", strokeorder.engine.Describe.stroke(Fixtures.HI, 2))
        assertEquals("starts at the top left", strokeorder.engine.Describe.travel(Fixtures.KUCHI, 1))
        assertEquals("Begin with stroke 1 — the left sweep.", Feedback.prompt(Fixtures.MIGI, 0))
    }

    @Test
    fun aLookAlikeWrittenFirstIsJudgedByWhereItIs() {
        val san = Fixtures.strokes(Fixtures.SAN)
        // 三's middle line written first, 10 units above its place, is still the middle line.
        assertEquals(Verdict.OutOfOrder(drawn = 1, expected = 0), Practice(Fixtures.SAN).submit(Perturb.shift(san[1], 0.0, -10.0)))
        // Its first line written 10 units low is still the first line: without clear evidence,
        // the first stroke keeps the benefit of the doubt.
        assertIs<Verdict.Accepted>(Practice(Fixtures.SAN).submit(Perturb.shift(san[0], 0.0, 10.0)))
        // Once strokes are written, the nearer look-alike wins: the bottom line written
        // where the middle one goes is the middle one.
        val p = Practice(Fixtures.SAN)
        p.submit(san[0])
        assertEquals(Verdict.Accepted(1, complete = false), p.submit(Perturb.shift(san[2], 0.0, -30.0)))
    }
}
