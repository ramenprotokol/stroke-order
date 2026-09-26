package strokeorder.ui

import kotlinx.browser.document
import kotlinx.browser.window

/** Canvas colours for the two papers. The page's CSS tokens mirror these for text and UI. */
class Theme(
    val name: String,
    /** The desk the sheet lies on (the page background; fills the saved image's edges). */
    val desk: String,
    /** Paper base colour. */
    val paper: String,
    /** "r,g,b" of the pale fibres that catch the light. */
    val fibreLight: String,
    /** "r,g,b" of the darker fibres and bark flecks. */
    val fibreDark: String,
    /** Ink colour and its "r,g,b" (for the soft wet edge). */
    val ink: String,
    val inkRgb: String,
    /** Faint 田 practice grid. */
    val grid: String,
    /** Vermilion "teacher's ink" used by Show me. */
    val demo: String,
    val seal: String,
    /** Opacity of the pale model character (usuzumi, "thin ink"). */
    val modelAlpha: Double,
    /** Whether the seal and paper texture multiply (washi) or sit on top (indigo). */
    val multiply: Boolean,
) {
    companion object {
        val WASHI = Theme(
            name = "washi",
            desk = "#e4dccb",
            paper = "#f4eee1",
            fibreLight = "255,253,247",
            fibreDark = "122,98,64",
            ink = "#16130f",
            inkRgb = "22,19,15",
            grid = "rgba(176,52,32,0.26)",
            demo = "#c2412a",
            seal = "#c0391f",
            modelAlpha = 0.105,
            multiply = true,
        )
        val INDIGO = Theme(
            name = "indigo",
            desk = "#0d1220",
            paper = "#1a2442",
            fibreLight = "150,176,230",
            fibreDark = "4,8,22",
            ink = "#f0e9d8",
            inkRgb = "240,233,216",
            grid = "rgba(255,150,120,0.24)",
            demo = "#ff8f6e",
            seal = "#d2442a",
            modelAlpha = 0.13,
            multiply = false,
        )

        /** The explicit choice on <html data-theme>, else the system preference. */
        fun current(): Theme {
            val explicit = document.documentElement?.getAttribute("data-theme")
            return when (explicit) {
                "indigo" -> INDIGO
                "washi" -> WASHI
                else -> if (window.matchMedia("(prefers-color-scheme: dark)").matches) INDIGO else WASHI
            }
        }
    }
}

/** Where the writing box sits on the sheet, as fractions of the sheet's width. */
object Layout {
    const val RATIO = 1.16
    const val BOX_LEFT = 0.07
    const val BOX_TOP = 0.07
    const val BOX = 0.86
    const val UNITS = 109.0
}

/** Maps KanjiVG frame units to device pixels on one canvas. */
class Frame(val scale: Double, val ox: Double, val oy: Double) {
    fun x(u: Double) = ox + u * scale
    fun y(u: Double) = oy + u * scale

    companion object {
        /** The frame for a sheet [cssWidth] wide drawn at [dpr] device pixels per CSS pixel. */
        fun forSheet(cssWidth: Double, dpr: Double): Frame {
            val w = cssWidth * dpr
            return Frame(Layout.BOX * w / Layout.UNITS, Layout.BOX_LEFT * w, Layout.BOX_TOP * w)
        }
    }
}

fun prefersReducedMotion(): Boolean = window.matchMedia("(prefers-reduced-motion: reduce)").matches
