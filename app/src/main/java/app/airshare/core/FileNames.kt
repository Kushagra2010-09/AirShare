package app.airshare.core

/** Safe handling of file names that arrive from another (untrusted) device. */
object FileNames {
    private const val MAX_LEN = 120
    private val BAD = Regex("[\\u0000-\\u001F\\u007F/\\\\:*?\"<>|]")

    /**
     * Strips directory components (path traversal), control characters and characters that are
     * illegal on common file systems. Never returns an empty string, "." or "..".
     */
    fun sanitize(raw: String?): String {
        var s = raw ?: ""
        s = s.substringAfterLast('/').substringAfterLast('\\')
        s = BAD.replace(s, "_").trim().trimStart('.')
        if (s.length > MAX_LEN) {
            val dot = s.lastIndexOf('.')
            val ext = if (dot > 0 && s.length - dot <= 12) s.substring(dot) else ""
            s = s.take(MAX_LEN - ext.length) + ext
        }
        return if (s.isBlank()) "file" else s
    }

    /** "photo.jpg" -> "photo (1).jpg" style candidate for the n-th duplicate (n >= 1). */
    fun withCounter(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }

    /** First name not present in [taken] (case-insensitive). */
    fun unique(name: String, taken: Set<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (name.lowercase() !in lower) return name
        var i = 1
        while (withCounter(name, i).lowercase() in lower) i++
        return withCounter(name, i)
    }
}
