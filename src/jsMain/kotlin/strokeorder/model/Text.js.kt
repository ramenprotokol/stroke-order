package strokeorder.model

actual fun nfkc(s: String): String = try {
    s.asDynamic().normalize("NFKC") as String
} catch (e: Throwable) {
    s
}
