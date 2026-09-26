package strokeorder

import strokeorder.engine.Practice
import strokeorder.engine.Verdict
import strokeorder.model.Kanji
import strokeorder.model.RefStroke
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the engine over every character in the committed KanjiVG snapshot (data/kanjivg),
 * not just the hand-picked fixtures. JVM-only because it reads files.
 */
class FullSetTest {
    private val SEEDS = (System.getenv("SWEEP_SEEDS") ?: "4").toInt()
    private val pathRe = Regex("""<path [^>]*?kvg:type="([^"]*)"[^>]*? d="([^"]+)"""")

    private val set: List<Kanji> by lazy {
        val dir = File("data/kanjivg")
        val files = dir.listFiles { f -> f.name.matches(Regex("[0-9a-f]{5}\\.svg")) }!!.sortedBy { it.name }
        files.map { f ->
            val svg = f.readText()
            val strokes = pathRe.findAll(svg).map { RefStroke(it.groupValues[2], it.groupValues[1], null) }.toList()
            val ch = String(Character.toChars(f.name.substring(0, 5).toInt(16)))
            Kanji(ch, "", "", emptyList(), emptyList(), strokes)
        }
    }

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
}
