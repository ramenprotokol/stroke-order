package strokeorder.util

/**
 * Small seeded PRNG (mulberry32). Deterministic on every platform, so a brush stroke,
 * the paper fibres and the tests all repeat exactly for the same seed.
 */
class Rng(seed: Int) {
    private var state = seed

    fun nextInt(): Int {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return t xor (t ushr 14)
    }

    /** Uniform in [0, 1). */
    fun next(): Double = (nextInt().toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0

    fun range(lo: Double, hi: Double) = lo + (hi - lo) * next()

    /** Approximately normal (sum of uniforms), mean 0, standard deviation 1. */
    fun gaussian(): Double {
        var s = 0.0
        repeat(6) { s += next() }
        return (s - 3.0) * 1.4142135623730951
    }
}
