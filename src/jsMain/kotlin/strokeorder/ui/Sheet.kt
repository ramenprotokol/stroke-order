package strokeorder.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.pointerevents.PointerEvent
import strokeorder.ink.BrushModel
import strokeorder.ink.BrushStroke
import strokeorder.ink.InputPoint
import strokeorder.model.Kanji
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** The pen: idle, or inking one stroke with one pointer. */
sealed class Pen {
    data object Idle : Pen()
    class Inking(val pointerId: Int, val points: MutableList<InputPoint>) : Pen()
}

/**
 * The writing sheet: five stacked canvases (paper, guide, ink, demo, wet) plus the seal,
 * and the pointer handling that turns a drag into brush input in the KanjiVG frame.
 */
class Sheet(
    private val root: HTMLElement,
    private val onStrokeStart: () -> Unit,
    private val onStrokeEnd: (List<InputPoint>) -> Unit,
) {
    private fun layer(id: String) = document.getElementById(id) as HTMLCanvasElement
    private val paper = layer("layer-paper")
    private val guide = layer("layer-guide")
    private val ink = layer("layer-ink")
    val demo = layer("layer-demo")
    private val wet = layer("layer-wet")
    private val seal = document.getElementById("seal") as HTMLCanvasElement

    var theme: Theme = Theme.current()
        private set
    var cssWidth = 0.0
        private set
    var dpr = 1.0
        private set
    val frame: Frame get() = Frame.forSheet(cssWidth, dpr)

    var pen: Pen = Pen.Idle
        private set
    private var frameRequested = false
    private var fadeTimer = 0
    private var fadeStartTimer = 0

    // What is drawn, so every layer can be repainted after a resize or theme change.
    private var kanji: Kanji? = null
    private var sealGlyph: Kanji? = null
    private val strokes = ArrayList<BrushStroke>()
    var showGrid = true
    var showModel = true

    init {
        root.addEventListener("pointerdown", { e -> down(e as PointerEvent) })
        root.addEventListener("pointermove", { e -> move(e as PointerEvent) })
        root.addEventListener("pointerup", { e -> up(e as PointerEvent) })
        root.addEventListener("pointercancel", { e -> cancel(e as PointerEvent) })
        root.addEventListener("lostpointercapture", { e -> cancel(e as PointerEvent) })
        // Stop the page from scrolling or zooming under the brush on touch screens.
        root.addEventListener("touchstart", { e: Event -> e.preventDefault() }, js("({ passive: false })"))
        root.addEventListener("contextmenu", { e: Event -> e.preventDefault() })
    }

    // ---- size and repaint ----

    /** Re-measures the sheet; repaints everything if its size or pixel density changed. */
    fun layout(force: Boolean = false) {
        val w = root.getBoundingClientRect().width
        // Cap device pixels per layer (about 4.2 million) so a huge screen can't exhaust memory.
        val maxDpr = kotlin.math.sqrt(4_200_000.0 / max(1.0, w * w * Layout.RATIO))
        val d = min(min(window.devicePixelRatio, 2.5), maxDpr).coerceAtLeast(1.0)
        if (!force && w == cssWidth && d == dpr) return
        cssWidth = w
        dpr = d
        val pw = round(w * d).toInt()
        val ph = round(w * Layout.RATIO * d).toInt()
        for (c in listOf(paper, guide, ink, demo, wet)) {
            c.width = pw
            c.height = ph
        }
        repaintAll()
    }

    fun setTheme(t: Theme) {
        theme = t
        repaintAll()
    }

    fun repaintAll() {
        if (cssWidth <= 0.0) return
        Paper.paint(paper, theme, dpr)
        paintGuide()
        repaintInk()
        Seal.paint(seal, round(cssWidth * 0.13 * dpr).toInt(), theme, sealGlyph)
    }

    fun setKanji(k: Kanji, sealKanji: Kanji?) {
        kanji = k
        sealGlyph = sealKanji
        strokes.clear()
        clearWet()
        clearDemo()
        hideSeal()
        paintGuide()
        repaintInk()
        Seal.paint(seal, round(cssWidth * 0.13 * dpr).toInt(), theme, sealGlyph)
    }

    fun paintGuide() {
        val g = guide.ctx2d()
        g.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        g.clearRect(0.0, 0.0, guide.width.toDouble(), guide.height.toDouble())
        val f = frame
        if (showGrid) {
            val x0 = f.x(0.0); val y0 = f.y(0.0); val size = Layout.UNITS * f.scale
            g.strokeStyle = theme.grid
            g.lineWidth = 1.0 * dpr
            g.strokeRect(x0, y0, size, size)
            g.asDynamic().setLineDash(arrayOf(5.0 * dpr, 5.0 * dpr))
            g.beginPath()
            g.moveTo(x0 + size / 2, y0); g.lineTo(x0 + size / 2, y0 + size)
            g.moveTo(x0, y0 + size / 2); g.lineTo(x0 + size, y0 + size / 2)
            g.stroke()
            g.asDynamic().setLineDash(arrayOf<Double>())
        }
        val k = kanji
        if (showModel && k != null) {
            k.strokes.forEachIndexed { i, s ->
                val model = BrushModel.finish(BrushModel.fromReference(s.points, s.type), seed = 900 + i, ending = BrushModel.endingFor(s.type))
                Ink.paint(g, model, f, theme.ink, theme.inkRgb, dpr, alpha = theme.modelAlpha, texture = false)
            }
        }
    }

    private fun repaintInk() {
        val g = ink.ctx2d()
        g.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        g.clearRect(0.0, 0.0, ink.width.toDouble(), ink.height.toDouble())
        for (s in strokes) Ink.paint(g, s, frame, theme.ink, theme.inkRgb, dpr)
    }

    // ---- ink ----

    val inkStrokes: List<BrushStroke> get() = strokes

    fun commit(stroke: BrushStroke) {
        strokes.add(stroke)
        clearWet()
        Ink.paint(ink.ctx2d(), stroke, frame, theme.ink, theme.inkRgb, dpr)
    }

    fun removeLast() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
        repaintInk()
    }

    fun clearInk() {
        strokes.clear()
        repaintInk()
    }

    /** Shows a rejected stroke, settled, then lets it fade from the paper. */
    fun reject(stroke: BrushStroke) {
        clearWet()
        Ink.paint(wet.ctx2d(), stroke, frame, theme.ink, theme.inkRgb, dpr, alpha = 0.85)
        val reduce = prefersReducedMotion()
        fadeStartTimer = window.setTimeout({ wet.classList.add("fading") }, if (reduce) 700 else 350)
        fadeTimer = window.setTimeout({ clearWet() }, if (reduce) 710 else 1100)
    }

    private fun clearWet() {
        window.clearTimeout(fadeTimer)
        window.clearTimeout(fadeStartTimer)
        wet.classList.remove("fading")
        val g = wet.ctx2d()
        g.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        g.clearRect(0.0, 0.0, wet.width.toDouble(), wet.height.toDouble())
    }

    fun clearDemo() {
        val g = demo.ctx2d()
        g.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        g.clearRect(0.0, 0.0, demo.width.toDouble(), demo.height.toDouble())
    }

    fun stampSeal() {
        seal.classList.remove("stamped")
        // restart the animation
        seal.asDynamic().offsetWidth
        seal.classList.add("stamped")
    }

    fun hideSeal() = seal.classList.remove("stamped")

    val sealCanvas: HTMLCanvasElement get() = seal

    // ---- pointer input ----

    private fun toInput(e: PointerEvent): InputPoint {
        val r = root.getBoundingClientRect()
        val box = Layout.BOX * r.width
        val u = (e.clientX - r.left - Layout.BOX_LEFT * r.width) / box * Layout.UNITS
        val v = (e.clientY - r.top - Layout.BOX_TOP * r.width) / box * Layout.UNITS
        val pressure = if (e.pointerType == "pen" && e.pressure > 0f) e.pressure.toDouble() else null
        return InputPoint(u, v, e.timeStamp.toDouble(), pressure)
    }

    private fun down(e: PointerEvent) {
        if (pen is Pen.Inking) return
        if (e.pointerType == "mouse" && e.button.toInt() != 0) return
        e.preventDefault()
        try { root.setPointerCapture(e.pointerId) } catch (_: Throwable) {}
        clearWet()
        pen = Pen.Inking(e.pointerId, mutableListOf(toInput(e)))
        document.body?.classList?.add("inking")
        onStrokeStart()
        requestPaint()
    }

    private fun move(e: PointerEvent) {
        val p = pen as? Pen.Inking ?: return
        if (e.pointerId != p.pointerId) return
        val getCoalesced: dynamic = e.asDynamic().getCoalescedEvents
        val coalesced: dynamic = if (getCoalesced != null) getCoalesced.call(e) else null
        val events: List<PointerEvent> =
            if (coalesced != null && coalesced.length as Int > 0) (coalesced.unsafeCast<Array<PointerEvent>>()).toList() else listOf(e)
        for (ev in events) {
            if (p.points.size >= BrushModel.MAX_INPUT) break
            p.points.add(toInput(ev))
        }
        requestPaint()
    }

    private fun up(e: PointerEvent) {
        val p = pen as? Pen.Inking ?: return
        if (e.pointerId != p.pointerId) return
        p.points.add(toInput(e))
        pen = Pen.Idle
        document.body?.classList?.remove("inking")
        onStrokeEnd(p.points.take(BrushModel.MAX_INPUT))
    }

    private fun cancel(e: PointerEvent) {
        val p = pen as? Pen.Inking ?: return
        if (e.pointerId != p.pointerId) return
        pen = Pen.Idle
        document.body?.classList?.remove("inking")
        clearWet()
    }

    private fun requestPaint() {
        if (frameRequested) return
        frameRequested = true
        window.requestAnimationFrame {
            frameRequested = false
            val p = pen as? Pen.Inking
            if (p != null) {
                val g = wet.ctx2d()
                g.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
                g.clearRect(0.0, 0.0, wet.width.toDouble(), wet.height.toDouble())
                Ink.paintLive(g, BrushModel.live(p.points), frame, theme, dpr)
            }
        }
    }
}
