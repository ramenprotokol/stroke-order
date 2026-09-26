package strokeorder.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import strokeorder.ink.BrushStroke
import strokeorder.model.Kanji
import kotlin.js.Promise
import kotlin.math.PI
import kotlin.math.round

/** Renders the sheet afresh at a fixed size and downloads it as a PNG. */
object Export {
    private const val CSS_WIDTH = 1000.0
    private const val DPR = 1.2

    fun savePng(
        kanji: Kanji,
        strokes: List<BrushStroke>,
        theme: Theme,
        showGrid: Boolean,
        sealKanji: Kanji?,
        sealed: Boolean,
        onResult: (String?) -> Unit,
    ) {
        val fonts: dynamic = document.asDynamic().fonts
        val ready: Promise<Any?> =
            if (fonts != null) (fonts.load("500 40px 'SO Serif JP'") as Promise<Any?>).catch { null } else Promise.resolve(null)
        ready.then {
            try {
                render(kanji, strokes, theme, showGrid, sealKanji, sealed, onResult)
            } catch (e: Throwable) {
                onResult(null)
            }
        }
    }

    private fun render(
        kanji: Kanji,
        strokes: List<BrushStroke>,
        theme: Theme,
        showGrid: Boolean,
        sealKanji: Kanji?,
        sealed: Boolean,
        onResult: (String?) -> Unit,
    ) {
        val w = round(CSS_WIDTH * DPR).toInt()
        val h = round(CSS_WIDTH * Layout.RATIO * DPR).toInt()
        val c = newCanvas(w, h)
        val g = c.ctx2d()
        Paper.paint(c, theme, DPR)
        // The desk behind the deckled edge, so the image has no transparent corners.
        g.save()
        g.globalCompositeOperation = "destination-over"
        g.fillStyle = theme.desk
        g.fillRect(0.0, 0.0, w.toDouble(), h.toDouble())
        g.restore()
        val f = Frame.forSheet(CSS_WIDTH, DPR)
        if (showGrid) {
            val x0 = f.x(0.0); val y0 = f.y(0.0); val size = Layout.UNITS * f.scale
            g.strokeStyle = theme.grid
            g.lineWidth = DPR
            g.strokeRect(x0, y0, size, size)
            g.asDynamic().setLineDash(arrayOf(5.0 * DPR, 5.0 * DPR))
            g.beginPath()
            g.moveTo(x0 + size / 2, y0); g.lineTo(x0 + size / 2, y0 + size)
            g.moveTo(x0, y0 + size / 2); g.lineTo(x0 + size, y0 + size / 2)
            g.stroke()
            g.asDynamic().setLineDash(arrayOf<Double>())
        }
        for (s in strokes) Ink.paint(g, s, f, theme.ink, theme.inkRgb, DPR)

        if (sealed) {
            val size = round(w * 0.13).toInt()
            val sc = newCanvas(size, size)
            Seal.paint(sc, size, theme, sealKanji)
            g.save()
            if (theme.multiply) g.globalCompositeOperation = "multiply"
            g.translate(w * 0.07 + size / 2.0, h * 0.843 + size / 2.0)
            g.rotate(-3 * PI / 180)
            g.drawImage(sc, -size / 2.0, -size / 2.0)
            g.restore()
        }

        // The printed caption in the bottom band, as on screen.
        g.save()
        g.fillStyle = if (theme.multiply) "#a3301c" else "#ff9a7e"
        g.asDynamic().textAlign = "right"
        g.asDynamic().textBaseline = "alphabetic"
        val base = h * 0.865 + 0.03 * w
        g.font = "400 ${0.024 * w}px 'SO Sans', 'Noto Sans', sans-serif"
        val meaning = kanji.meaning
        g.fillText(meaning, w * 0.93, base)
        val mw = g.measureText(meaning).width
        g.font = "500 ${0.036 * w}px 'SO Serif JP', 'Noto Serif JP', serif"
        g.fillText(kanji.char, w * 0.93 - mw - 0.012 * w, base)
        g.restore()

        c.toBlob({ blob ->
            if (blob == null) {
                onResult(null)
            } else {
                val url = URL.createObjectURL(blob)
                val a = document.createElement("a") as HTMLAnchorElement
                a.href = url
                a.download = "stroke-order-u${kanji.char.codePointAt0().toString(16)}.png"
                document.body?.appendChild(a)
                a.click()
                a.remove()
                window.setTimeout({ URL.revokeObjectURL(url) }, 4000)
                onResult(a.download)
            }
        }, "image/png")
    }
}

private fun String.codePointAt0(): Int {
    val hi = this[0].code
    if (hi in 0xD800..0xDBFF && length > 1) return ((hi - 0xD800) shl 10) + (this[1].code - 0xDC00) + 0x10000
    return hi
}
