package com.ceasle.intellicursor.ui

/**
 * Minimal Markdown support for agent replies: enough for headings, lists, emphasis,
 * inline code, links and fenced code blocks. Fenced blocks are split out so the panel
 * can render them as real code components with their own toolbar.
 */
object MarkdownRenderer {

    sealed interface Segment
    data class Prose(val markdown: String) : Segment
    data class Code(val language: String, val code: String, val closed: Boolean) : Segment

    fun split(markdown: String): List<Segment> {
        val out = mutableListOf<Segment>()
        val prose = StringBuilder()
        var code: StringBuilder? = null
        var lang = ""

        for (line in markdown.split("\n")) {
            val fence = line.trimStart().startsWith("```")
            if (code == null && fence) {
                if (prose.isNotBlank()) out += Prose(prose.toString())
                prose.clear()
                code = StringBuilder()
                lang = line.trimStart().removePrefix("```").trim()
            } else if (code != null && fence) {
                out += Code(lang, code.toString().removeSuffix("\n"), closed = true)
                code = null
            } else if (code != null) {
                code.append(line).append('\n')
            } else {
                prose.append(line).append('\n')
            }
        }
        // A fence still open means the reply is mid-stream; show what has arrived so far.
        if (code != null) out += Code(lang, code.toString().removeSuffix("\n"), closed = false)
        if (prose.isNotBlank()) out += Prose(prose.toString())
        return out
    }

    fun toHtml(markdown: String): String {
        val html = StringBuilder()
        var list: String? = null
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) html.append("<p>").append(inline(paragraph.toString().trim())).append("</p>")
            paragraph.clear()
        }
        fun closeList() {
            list?.let { html.append("</$it>") }
            list = null
        }

        for (raw in markdown.split("\n")) {
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            val heading = Regex("^(#{1,6})\\s+(.*)").find(trimmed)
            val bullet = Regex("^[-*+]\\s+(.*)").find(trimmed)
            val ordered = Regex("^\\d+[.)]\\s+(.*)").find(trimmed)

            when {
                trimmed.isEmpty() -> { flushParagraph(); closeList() }
                heading != null -> {
                    flushParagraph(); closeList()
                    val level = (heading.groupValues[1].length + 1).coerceAtMost(6)
                    html.append("<h$level>").append(inline(heading.groupValues[2])).append("</h$level>")
                }
                trimmed.matches(Regex("^(-{3,}|\\*{3,})$")) -> { flushParagraph(); closeList(); html.append("<hr/>") }
                bullet != null || ordered != null -> {
                    flushParagraph()
                    val tag = if (bullet != null) "ul" else "ol"
                    if (list != tag) { closeList(); html.append("<$tag>"); list = tag }
                    html.append("<li>").append(inline((bullet ?: ordered)!!.groupValues[1])).append("</li>")
                }
                trimmed.startsWith(">") -> {
                    flushParagraph(); closeList()
                    html.append("<blockquote>").append(inline(trimmed.removePrefix(">").trim())).append("</blockquote>")
                }
                else -> { closeList(); paragraph.append(trimmed).append(' ') }
            }
        }
        flushParagraph(); closeList()
        return html.toString()
    }

    private fun inline(text: String): String {
        val codeSpans = mutableListOf<String>()
        var s = Regex("`([^`]+)`").replace(text) {
            codeSpans += it.groupValues[1]
            "\u0000${codeSpans.size - 1}\u0000"
        }
        s = escape(s)
        s = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)").replace(s) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        s = Regex("\\*\\*(.+?)\\*\\*").replace(s) { "<b>${it.groupValues[1]}</b>" }
        s = Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])").replace(s) { "<i>${it.groupValues[1]}</i>" }
        s = Regex("\u0000(\\d+)\u0000").replace(s) { "<code>${escape(codeSpans[it.groupValues[1].toInt()])}</code>" }
        return s
    }

    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
