package strokeorder.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLMetaElement
import strokeorder.model.Catalog

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
    // Loading the data and starting the page fail for different reasons, so they say different things.
    window.fetch(url).then { res ->
        if (!res.ok) throw LoadFailed("the server answered ${res.status}")
        res.text()
    }.then { text -> Data.parse(text) }.then({ catalog: Catalog ->
        try {
            App(catalog).start()
        } catch (e: Throwable) {
            console.error("stroke-order: the page failed while starting", e)
            fail(
                "Sorry — the page hit an error while starting (${e.message ?: "unknown error"}). " +
                    "Reloading may help; if it doesn't, this browser may be missing something the page needs.",
            )
        }
    }, { e: Throwable ->
        console.error("stroke-order: could not load the stroke data", e)
        fail(
            when (e) {
                is Data.Invalid -> "The stroke data arrived but couldn't be read (${e.message}). Please reload the page."
                is LoadFailed -> "The stroke data couldn't be loaded (${e.message}). Please reload the page."
                // fetch() itself rejects only when the request never completes.
                else -> "The stroke data couldn't be loaded (${e.message ?: "network error"}). Check your connection and reload the page."
            },
        )
    })
}

/** The data request completed but did not succeed. */
private class LoadFailed(message: String) : Exception(message)
