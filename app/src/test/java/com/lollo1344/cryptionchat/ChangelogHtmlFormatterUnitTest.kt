package com.lollo1344.cryptionchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogHtmlFormatterUnitTest {

    @Test
    fun stripsHeadStyleScriptAndCommentsWhileKeepingContent() {
        val raw = """
            <!DOCTYPE html>
            <html lang="it">
            <head>
                <meta charset="UTF-8">
                <title>Changelog</title>
                <style>body { background: #121212; color: #fff; }</style>
                <script>console.log('ignored');</script>
            </head>
            <body>
                <!-- commento nascosto -->
                <h1>Versione 78.8 (beta)</h1>
                <ul>
                    <li><strong>Fix batteria</strong>: meno wakeup</li>
                    <li>Changelog nativo leggero</li>
                </ul>
                <hr/>
                <p>Dettagli su <a href="https://example.com">GitHub</a>.</p>
            </body>
            </html>
        """.trimIndent()

        val formatted = ChangelogHtmlFormatter.prepareForAndroidHtml(raw)

        assertFalse("CSS must be stripped", formatted.contains("background: #121212"))
        assertFalse("Script must be stripped", formatted.contains("console.log"))
        assertFalse("Comments must be stripped", formatted.contains("commento nascosto"))
        assertTrue("Heading must be preserved", formatted.contains("<h1>Versione 78.8 (beta)</h1>"))
        assertTrue("Bullets must be rendered cleanly", formatted.contains("• <strong>Fix batteria</strong>: meno wakeup<br>"))
        assertTrue("Links must be preserved", formatted.contains("https://example.com"))
    }

    @Test
    fun blankInputReturnsEmptyString() {
        assertTrue(ChangelogHtmlFormatter.prepareForAndroidHtml("   \n\t  ").isEmpty())
    }
}
