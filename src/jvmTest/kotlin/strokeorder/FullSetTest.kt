package strokeorder

import strokeorder.engine.Practice
import strokeorder.engine.Verdict
import strokeorder.model.Kanji
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the engine over every character in the committed KanjiVG snapshot (data/kanjivg),
 * not just the hand-picked fixtures. JVM-only because it reads files.
 */
class FullSetTest {
    private val SEEDS = (System.getenv("SWEEP_SEEDS") ?: "4").toInt()
    private val set: List<Kanji> get() = KanjiSet.all

    @Test
    fun snapshotHasTheCuratedEighty() {
        assertEquals(80, set.size)
        assertTrue(set.all { it.strokeCount >= 1 })
    }

    @Test
    fun everyCharacterCompletesFromItsReferenceStrokes() {
        val failures = set.filterNot { k ->
            val p = Practice(k)
            k.strokes.all { p.submit(it.points) is Verdict.Accepted } && p.isComplete
        }
        assertEquals(emptyList(), failures.map { it.char }, "clean reference writing")
    }

    @Test
    fun everyCharacterCompletesWhenWrittenSloppily() {
        val failures = mutableListOf<String>()
        for (k in set) for (seed in 1..SEEDS) {
            val p = Practice(k)
            val verdicts = Perturb.sloppy(k.strokes.map { it.points }, seed * 31 + k.strokeCount).map { p.submit(it) }
            if (!p.isComplete) failures += "${k.char}#$seed ${verdicts.firstOrNull { it !is Verdict.Accepted }}"
        }
        println("sloppy writing: ${set.size * SEEDS - failures.size}/${set.size * SEEDS} completed")
        assertEquals(emptyList(), failures)
    }

    @Test
    fun skippingAheadIsDetectedAtEveryStepOfEveryCharacter() {
        var checks = 0
        val failures = mutableListOf<String>()
        for (k in set) for (i in 0 until k.strokeCount - 1) {
            // Write strokes 1..i correctly, then skip one: stroke i+2 instead of i+1.
            val p = Practice(k)
            for (j in 0 until i) p.submit(k.strokes[j].points)
            val v = p.submit(k.strokes[i + 1].points)
            checks++
            if (v !is Verdict.OutOfOrder) failures += "${k.char} step ${i + 1}: $v"
        }
        println("skip-ahead detection: ${checks - failures.size}/$checks")
        assertEquals(emptyList(), failures)
    }

    @Test
    fun reversedStrokesAreCaughtWhereDirectionIsJudged() {
        var checks = 0
        val failures = mutableListOf<String>()
        for (k in set) for (i in 0 until k.strokeCount) {
            val s = k.strokes[i]
            if (s.type.startsWith("㇔") || s.length < 13.0) continue
            val p = Practice(k)
            for (j in 0 until i) p.submit(k.strokes[j].points)
            val v = p.submit(s.points.reversed())
            checks++
            if (v != Verdict.WrongDirection(i)) failures += "${k.char} stroke ${i + 1} (${s.type}): $v"
        }
        println("reversed-stroke detection: ${checks - failures.size}/$checks")
        assertEquals(emptyList(), failures)
    }

    /**
     * The same two checks with hand-drawn (shrunk, shifted, rotated, jittery) strokes.
     * These are measured rates, not guarantees: a sloppy stroke can genuinely land
     * where a different stroke belongs.
     */
    @Test
    fun detectionRatesWithSloppyWriting() {
        var skipChecks = 0; var skipHits = 0; var revChecks = 0; var revHits = 0
        val misses = mutableListOf<String>()
        for (k in set) for (seed in 1..SEEDS) {
            val sloppy = Perturb.sloppy(k.strokes.map { it.points }, seed * 17 + k.strokeCount)
            for (i in 0 until k.strokeCount) {
                val prefix = { Practice(k).also { p -> for (j in 0 until i) p.submit(sloppy[j]) } }
                if (i + 1 < k.strokeCount) {
                    skipChecks++
                    val v = prefix().submit(sloppy[i + 1])
                    if (v is Verdict.OutOfOrder) skipHits++ else misses += "skip ${k.char}#$seed@${i + 1}: $v"
                }
                val s = k.strokes[i]
                if (!s.type.startsWith("㇔") && s.length >= 13.0) {
                    revChecks++
                    val v = prefix().submit(sloppy[i].reversed())
                    if (v == Verdict.WrongDirection(i)) revHits++ else misses += "rev ${k.char}#$seed@${i + 1}: $v"
                }
            }
        }
        println("sloppy skip-ahead detection: $skipHits/$skipChecks; sloppy reversed detection: $revHits/$revChecks")
        misses.take(15).forEach { println("  $it") }
        assertTrue(skipHits >= skipChecks * 0.97, "skip-ahead rate")
        assertTrue(revHits >= revChecks * 0.97, "reversed rate")
    }

    /**
     * Dots and other short marks, dabbed the way a finger often does: much shorter than
     * KanjiVG's drawn dot (3, 5 and 8 units), centred where it belongs and pointing its
     * way, after the strokes before it were written. Every one should count.
     */
    @Test
    fun shortDabsOnDotsAndMarksAreAccepted() {
        var checks = 0
        val misses = mutableListOf<String>()
        for (k in set) for (i in 0 until k.strokeCount) {
            val s = k.strokes[i]
            if (!s.type.startsWith("㇔") && s.length >= strokeorder.engine.MatchConfig().markLength) continue
            val even = strokeorder.engine.Geometry.resample(s.points, 32)
            val c = strokeorder.engine.Geometry.centroid(even)
            val d = even.last() - even.first()
            val u = d * (1.0 / d.length())
            for (len in listOf(3.0, 5.0, 8.0)) {
                val p = Practice(k)
                for (j in 0 until i) p.submit(k.strokes[j].points)
                val dab = line(c.x - u.x * len / 2, c.y - u.y * len / 2, c.x + u.x * len / 2, c.y + u.y * len / 2)
                checks++
                val v = p.submit(dab)
                if (v !is Verdict.Accepted) misses += "${k.char} stroke ${i + 1} (${s.type}, ${len.toInt()} units): $v"
            }
        }
        println("short dabs on dots and marks: ${checks - misses.size}/$checks accepted")
        assertEquals(emptyList(), misses)
    }
}
