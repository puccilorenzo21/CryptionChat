package com.lollo1344.cryptionchat

/**
 * Converte le pagine HTML dei changelog (stabile/beta) in HTML pulito e leggibile
 * per il rendering nativo tramite `HtmlCompat.fromHtml(...)` in una `TextView`,
 * evitando di caricare il motore Chromium (`WebView`, ~42 thread e ~65 MB nativi/ZRAM).
 */
object ChangelogHtmlFormatter {

    private val DOCTYPE_REGEX = Regex("(?is)<!DOCTYPE[^>]*>")
    private val COMMENT_REGEX = Regex("(?s)<!--.*?-->")
    private val HEAD_REGEX = Regex("(?is)<head\\b[^>]*>.*?</head>")
    private val STYLE_REGEX = Regex("(?is)<style\\b[^>]*>.*?</style>")
    private val SCRIPT_REGEX = Regex("(?is)<script\\b[^>]*>.*?</script>")
    private val NOSCRIPT_REGEX = Regex("(?is)<noscript\\b[^>]*>.*?</noscript>")
    private val HR_REGEX = Regex("(?is)<hr\\b[^>]*/?>")
    private val UL_OPEN_REGEX = Regex("(?is)<[uo]l\\b[^>]*>")
    private val UL_CLOSE_REGEX = Regex("(?is)</[uo]l>")
    private val LI_OPEN_REGEX = Regex("(?is)<li\\b[^>]*>")
    private val LI_CLOSE_REGEX = Regex("(?is)</li>")
    private val MULTI_BR_REGEX = Regex("(?is)(<br\\s*/?>\\s*){3,}")
    private val LEADING_BR_REGEX = Regex("(?is)^(\\s|<br\\s*/?>)+")

    /**
     * Prepara l'HTML remoto rimuovendo intestazioni/CSS/script e formattando liste e separatori
     * in modo uniforme su tutte le versioni di Android.
     */
    fun prepareForAndroidHtml(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""

        var cleaned = rawHtml
            .replace(DOCTYPE_REGEX, "")
            .replace(COMMENT_REGEX, "")
            .replace(HEAD_REGEX, "")
            .replace(STYLE_REGEX, "")
            .replace(SCRIPT_REGEX, "")
            .replace(NOSCRIPT_REGEX, "")

        // Rende elenchi puntati coerenti su tutte le versioni Android/OEM
        cleaned = cleaned
            .replace(UL_OPEN_REGEX, "<br>")
            .replace(UL_CLOSE_REGEX, "<br>")
            .replace(LI_OPEN_REGEX, "• ")
            .replace(LI_CLOSE_REGEX, "<br>")
            .replace(HR_REGEX, "<br>────────────────────<br>")
            .replace(MULTI_BR_REGEX, "<br><br>")
            .replace(LEADING_BR_REGEX, "")
            .trim()

        return cleaned
    }
}
