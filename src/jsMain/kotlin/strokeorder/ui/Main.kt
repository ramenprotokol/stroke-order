package strokeorder.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLMetaElement

fun main() {
    val feedback = document.getElementById("feedback")
    fun fail(message: String) {
        feedback?.textContent = message
        feedback?.className = "feedback miss"
        document.body?.setAttribute("data-ready", "error")
    }
    val url = (document.querySelector("meta[name=stroke-data]") as? HTMLMetaElement)?.content
    if (url.isNullOrEmpty()) {
        fail("This page is missing its stroke data link.")
        return
    }
    window.fetch(url).then { res ->
        if (!res.ok) throw Data.Invalid("HTTP ${res.status}")
        res.text()
    }.then { text ->
        App(Data.parse(text)).start()
    }.catch { e ->
        console.error("stroke-order: could not start", e)
        fail("The stroke data couldn't be loaded (${e.message ?: "unknown error"}). Please reload the page.")
    }
}
