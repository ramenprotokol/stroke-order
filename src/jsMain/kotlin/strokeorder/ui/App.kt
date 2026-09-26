package strokeorder.ui

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import strokeorder.engine.Feedback
import strokeorder.engine.Point
import strokeorder.engine.Practice
import strokeorder.engine.StrokeState
import strokeorder.engine.Verdict
import strokeorder.ink.BrushModel
import strokeorder.ink.InputPoint
import strokeorder.model.Catalog
import strokeorder.model.Kanji
import strokeorder.model.Search
import strokeorder.model.ShareLink

private fun el(id: String): HTMLElement = document.getElementById(id) as HTMLElement
private fun button(id: String): HTMLButtonElement = document.getElementById(id) as HTMLButtonElement

/** Small wrapper: storage can be missing or throw (private mode, blocked cookies). */
private object Prefs {
    fun get(key: String): String? = try { localStorage.getItem("stroke-order:$key") } catch (e: Throwable) { null }
    fun set(key: String, value: String) { try { localStorage.setItem("stroke-order:$key", value) } catch (e: Throwable) {} }
}

class App(private val catalog: Catalog) {
    private val sheet = Sheet(el("sheet"), ::onStrokeStart, ::onStrokeEnd)
    private val demo = Demo(sheet, ::onDemoFinished)
    private val sealKanji = catalog["正"]

    private lateinit var kanji: Kanji
    private lateinit var practice: Practice
    private var strokeSeed = 1
    private val written: MutableSet<String> = (Prefs.get("written") ?: "").take(400).map { it.toString() }.filter { catalog[it] != null }.toMutableSet()

    private val feedback = el("feedback")
    private val progress = el("progress")
    private val groups = el("groups")
    private val search = document.getElementById("search") as HTMLInputElement
    private val searchStatus = el("search-status")
    private val shareNote = el("share-note")
    private val charButtons = HashMap<String, MutableList<HTMLButtonElement>>()

    fun start() {
        sheet.showGrid = Prefs.get("grid") != "off"
        sheet.showModel = Prefs.get("model") != "off"
        syncToggles()
        buildPicker()
        wireControls()

        val first = when (val link = ShareLink.parse(window.location.hash) { catalog[it] != null }) {
            is ShareLink.Parsed.Valid -> catalog[link.char]!!
            is ShareLink.Parsed.Invalid -> { select(catalog.first, updateHash = false); say(link.message, "miss"); null }
            ShareLink.Parsed.None -> catalog.first
        }
        sheet.layout(force = true)
        if (first != null) select(first, updateHash = false)

        window.addEventListener("resize", { requestLayout() })
        window.addEventListener("hashchange", { onHashChange() })
        window.matchMedia("(prefers-color-scheme: dark)").asDynamic().addEventListener("change", { _: Event ->
            if (document.documentElement?.getAttribute("data-theme") == null) applyTheme()
        })
        // Repaint once the web fonts arrive (the stroke numbers use the serif).
        val fonts: dynamic = document.asDynamic().fonts
        if (fonts != null) fonts.ready.then { if (demo.visible && !demo.running) demo.play(kanji) }
        document.body?.setAttribute("data-ready", "1")
    }

    private var layoutQueued = false
    private fun requestLayout() {
        if (layoutQueued) return
        layoutQueued = true
        window.requestAnimationFrame {
            layoutQueued = false
            sheet.layout()
        }
    }

    // ---- selecting a character ----

    private fun select(k: Kanji, updateHash: Boolean = true) {
        demo.clear()
        kanji = k
        practice = Practice(k)
        sheet.setKanji(k, sealKanji)
        el("glyph").textContent = k.char
        el("meaning").textContent = k.meaning
        el("reading-on").textContent = k.on.joinToString("・").ifEmpty { "—" }
        el("reading-kun").textContent = k.kun.joinToString("・").ifEmpty { "—" }
        el("count").textContent = if (k.strokeCount == 1) "1 stroke" else "${k.strokeCount} strokes"
        el("caption-char").textContent = k.char
        el("caption-text").textContent = k.meaning
        val after = catalog.next(k)
        el("next-char").textContent = after.char
        button("btn-next").setAttribute("aria-label", "Next character: ${after.char}, ${after.shortMeaning}")
        for ((c, list) in charButtons) for (b in list) b.setAttribute("aria-current", (c == k.char).toString())
        if (updateHash) window.history.replaceState(null, "", ShareLink.fragment(k.char))
        shareNote.textContent = ""
        say(Feedback.prompt(k, 0), "")
        refreshProgress()
    }

    private fun onHashChange() {
        when (val link = ShareLink.parse(window.location.hash) { catalog[it] != null }) {
            is ShareLink.Parsed.Valid -> if (link.char != kanji.char) select(catalog[link.char]!!, updateHash = false)
            is ShareLink.Parsed.Invalid -> say(link.message, "miss")
            ShareLink.Parsed.None -> {}
        }
    }

    // ---- strokes ----

    private fun onStrokeStart() {
        if (demo.visible) demo.clear()
    }

    private fun onStrokeEnd(input: List<InputPoint>) {
        val points = input.map { Point(it.x, it.y) }
        val verdict = practice.submit(points)
        val brush = BrushModel.finish(input, seed = strokeSeed++)
        if (verdict is Verdict.Accepted) {
            sheet.commit(brush)
            if (verdict.complete) complete()
        } else {
            sheet.reject(brush)
        }
        val tone = when (verdict) {
            is Verdict.Accepted -> if (verdict.complete) "done" else "good"
            else -> "miss"
        }
        say(Feedback.forVerdict(kanji, verdict, practice.slips), tone)
        refreshProgress()
    }

    private fun complete() {
        sheet.stampSeal()
        written.add(kanji.char)
        Prefs.set("written", written.joinToString(""))
        charButtons[kanji.char]?.forEach { it.classList.add("written") }
    }

    private fun undo() {
        demo.clear()
        if (practice.undo() == null) return
        sheet.removeLast()
        sheet.hideSeal()
        say(Feedback.prompt(kanji, practice.next), "")
        refreshProgress()
    }

    private fun clear() {
        demo.clear()
        practice.reset()
        sheet.clearInk()
        sheet.hideSeal()
        say(Feedback.prompt(kanji, 0), "")
        refreshProgress()
    }

    private fun onDemoFinished() {
        button("btn-show").textContent = "Show me"
    }

    // ---- page text ----

    private fun say(text: String, tone: String) {
        feedback.textContent = text
        feedback.className = "feedback" + if (tone.isNotEmpty()) " $tone" else ""
    }

    private fun refreshProgress() {
        progress.innerHTML = ""
        for (i in 0 until kanji.strokeCount) {
            val li = document.createElement("li")
            li.textContent = (i + 1).toString()
            val state = practice.state(i)
            val (cls, word) = when (state) {
                is StrokeState.Written -> "done" to "written"
                StrokeState.Next -> "next" to "next"
                StrokeState.Pending -> "" to "not yet written"
            }
            if (cls.isNotEmpty()) li.className = cls
            li.setAttribute("aria-label", "Stroke ${i + 1}, $word")
            progress.appendChild(li)
        }
        val sheetEl = el("sheet")
        sheetEl.setAttribute("data-progress", "${practice.next}/${practice.total}")
        sheetEl.setAttribute("data-state", if (practice.isComplete) "complete" else "writing")
        sheetEl.setAttribute(
            "aria-label",
            "Writing sheet for ${kanji.char} (${kanji.shortMeaning}): ${practice.next} of ${practice.total} strokes written" +
                if (practice.isComplete) ", sealed." else ".",
        )
        button("btn-undo").disabled = practice.next == 0
    }

    // ---- controls ----

    private fun wireControls() {
        button("btn-show").onclick = {
            if (demo.running) {
                demo.clear()
                button("btn-show").textContent = "Show me"
            } else {
                demo.play(kanji)
                if (demo.running) button("btn-show").textContent = "Stop"
            }
            null
        }
        button("btn-undo").onclick = { undo(); null }
        button("btn-clear").onclick = { clear(); null }
        button("btn-next").onclick = { select(catalog.next(kanji)); null }
        button("tgl-grid").onclick = {
            sheet.showGrid = !sheet.showGrid
            Prefs.set("grid", if (sheet.showGrid) "on" else "off")
            syncToggles(); sheet.paintGuide(); null
        }
        button("tgl-model").onclick = {
            sheet.showModel = !sheet.showModel
            Prefs.set("model", if (sheet.showModel) "on" else "off")
            syncToggles(); sheet.paintGuide(); null
        }
        button("tgl-paper").onclick = {
            val next = if (sheet.theme.name == "indigo") "washi" else "indigo"
            document.documentElement?.setAttribute("data-theme", next)
            Prefs.set("paper", next)
            applyTheme(); null
        }
        button("btn-save").onclick = {
            shareNote.textContent = "Preparing the image…"
            Export.savePng(kanji, sheet.inkStrokes.toList(), sheet.theme, sheet.showGrid, sealKanji, practice.isComplete) { name ->
                shareNote.textContent = if (name != null) "Saved $name." else "Sorry — this browser couldn't make the image."
            }
            null
        }
        button("btn-link").onclick = { copyLink(); null }
        document.addEventListener("keydown", { e ->
            val k = e as KeyboardEvent
            if ((k.ctrlKey || k.metaKey) && k.key.lowercase() == "z" && document.activeElement != search) {
                k.preventDefault(); undo()
            }
        })
        search.addEventListener("input", { runSearch() })
    }

    private fun applyTheme() {
        sheet.setTheme(Theme.current())
        syncToggles()
        if (demo.visible) demo.play(kanji)
    }

    private fun syncToggles() {
        button("tgl-grid").setAttribute("aria-pressed", sheet.showGrid.toString())
        button("tgl-model").setAttribute("aria-pressed", sheet.showModel.toString())
        button("tgl-paper").setAttribute("aria-pressed", (Theme.current().name == "indigo").toString())
    }

    private fun copyLink() {
        val url = window.location.href.substringBefore('#') + ShareLink.fragment(kanji.char)
        val clip: dynamic = window.navigator.asDynamic().clipboard
        if (clip != null) {
            (clip.writeText(url) as kotlin.js.Promise<Any?>).then(
                { shareNote.textContent = "Link copied." },
                { shareNote.textContent = url },
            )
        } else {
            shareNote.textContent = url
        }
    }

    // ---- picker ----

    private fun charButton(k: Kanji): HTMLButtonElement {
        val b = document.createElement("button") as HTMLButtonElement
        b.type = "button"
        b.className = "char" + if (k.char in written) " written" else ""
        b.lang = "ja"
        b.textContent = k.char
        b.setAttribute("aria-label", "${k.char}, ${k.shortMeaning}, ${k.strokeCount} ${if (k.strokeCount == 1) "stroke" else "strokes"}")
        b.setAttribute("aria-current", "false")
        b.onclick = { select(k); scrollSheetIntoViewOnPhone(); null }
        charButtons.getOrPut(k.char) { mutableListOf() }.add(b)
        return b
    }

    private fun scrollSheetIntoViewOnPhone() {
        if (window.innerWidth < 900) el("sheet").asDynamic().scrollIntoView(js("({ block: 'center', behavior: 'smooth' })"))
    }

    private fun buildPicker() {
        groups.innerHTML = ""
        for (g in catalog.groups) {
            val sec = document.createElement("section")
            sec.className = "group"
            val h = document.createElement("h3")
            h.appendChild(document.createTextNode(g.title))
            val jp = document.createElement("span")
            jp.setAttribute("lang", "ja")
            jp.textContent = g.jp
            h.appendChild(jp)
            sec.appendChild(h)
            val grid = document.createElement("div")
            grid.className = "chars"
            for (k in g.kanji) grid.appendChild(charButton(k))
            sec.appendChild(grid)
            groups.appendChild(sec)
        }
        val results = document.createElement("div")
        results.id = "results"
        results.className = "chars"
        results.setAttribute("hidden", "")
        groups.parentElement?.insertBefore(results, groups)
    }

    private fun runSearch() {
        val raw = search.value
        val results = el("results")
        val q = Search.normalize(raw)
        // Rebuild the result buttons (at most 80) without touching the grouped list.
        results.innerHTML = ""
        for (list in charButtons.values) list.removeAll { it.parentElement == null }
        if (q.isEmpty()) {
            results.setAttribute("hidden", "")
            groups.removeAttribute("hidden")
            searchStatus.textContent = ""
            return
        }
        val found = catalog.search(raw)
        groups.setAttribute("hidden", "")
        results.removeAttribute("hidden")
        for (k in found) {
            val b = charButton(k)
            b.setAttribute("aria-current", (k.char == kanji.char).toString())
            results.appendChild(b)
        }
        searchStatus.textContent = when (found.size) {
            0 -> "Nothing in this set matches “${raw.take(Search.MAX_QUERY).trim()}”. Try a meaning such as water, or a reading such as mizu or みず."
            1 -> "1 character matches."
            else -> "${found.size} characters match."
        }
    }
}
