package com.siamaehwa.nimstudio.core

sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
}

sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Link(val text: String, val url: String) : MdInline
}

object MarkdownParser {
    fun parse(source: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        val code = StringBuilder()
        var inCode = false
        var codeLang = ""

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks.add(MdBlock.Paragraph(paragraph.toString().trim('\n')))
            }
            paragraph.setLength(0)
        }

        for (line in source.split('\n')) {
            val trimmed = line.trimStart()
            if (!inCode && trimmed.startsWith("```")) {
                flushParagraph()
                inCode = true
                codeLang = trimmed.removePrefix("```").trim()
                code.setLength(0)
                continue
            }
            if (inCode) {
                if (trimmed.startsWith("```")) {
                    blocks.add(MdBlock.Code(codeLang, code.toString().trimEnd('\n')))
                    inCode = false
                    codeLang = ""
                    code.setLength(0)
                } else {
                    code.append(line).append('\n')
                }
                continue
            }
            paragraph.append(line).append('\n')
        }

        if (inCode) {
            blocks.add(MdBlock.Code(codeLang, code.toString().trimEnd('\n')))
        } else {
            flushParagraph()
        }
        return blocks
    }
}

object InlineMarkdown {

    fun parse(source: String): List<MdInline> = parseRange(source, 0, source.length)

    private fun parseRange(s: String, start: Int, end: Int): List<MdInline> {
        val out = ArrayList<MdInline>()
        val buf = StringBuilder()

        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(MdInline.Text(buf.toString()))
                buf.setLength(0)
            }
        }

        var i = start
        while (i < end) {
            val c = s[i]

            if (c == '\\' && i + 1 < end) {
                buf.append(s[i + 1])
                i += 2
                continue
            }

            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close in (i + 1) until end) {
                    flush()
                    out.add(MdInline.Code(s.substring(i + 1, close)))
                    i = close + 1
                    continue
                }
            }

            if (c == '*' && s.startsWith("**", i)) {
                val close = s.indexOf("**", i + 2)
                if (close in (i + 2) until end) {
                    flush()
                    out.add(MdInline.Bold(parseRange(s, i + 2, close)))
                    i = close + 2
                    continue
                }
            }

            if (c == '~' && s.startsWith("~~", i)) {
                val close = s.indexOf("~~", i + 2)
                if (close in (i + 2) until end) {
                    flush()
                    out.add(MdInline.Strike(parseRange(s, i + 2, close)))
                    i = close + 2
                    continue
                }
            }

            if (c == '*') {
                val close = findClosingStar(s, i + 1, end)
                if (close > i + 1) {
                    flush()
                    out.add(MdInline.Italic(parseRange(s, i + 1, close)))
                    i = close + 1
                    continue
                }
            }

            if (c == '_' && (i == start || !s[i - 1].isLetterOrDigit())) {
                val close = findClosingUnderscore(s, i + 1, end)
                if (close > i + 1) {
                    flush()
                    out.add(MdInline.Italic(parseRange(s, i + 1, close)))
                    i = close + 1
                    continue
                }
            }

            if (c == '[') {
                val closeBracket = s.indexOf(']', i + 1)
                if (closeBracket in (i + 1) until end && closeBracket + 1 < end && s[closeBracket + 1] == '(') {
                    val closeParen = s.indexOf(')', closeBracket + 2)
                    if (closeParen in (closeBracket + 2) until end) {
                        val url = s.substring(closeBracket + 2, closeParen).trim()
                        if (url.isNotEmpty() && !url.contains(' ')) {
                            flush()
                            out.add(MdInline.Link(s.substring(i + 1, closeBracket), url))
                            i = closeParen + 1
                            continue
                        }
                    }
                }
            }

            buf.append(c)
            i++
        }
        flush()
        return out
    }

    private fun findClosingStar(s: String, from: Int, end: Int): Int {
        var j = from
        while (j < end) {
            if (s[j] == '*') {
                val doubled = (j + 1 < end && s[j + 1] == '*') || (j - 1 >= from && s[j - 1] == '*')
                if (!doubled && j > from) return j
            }
            j++
        }
        return -1
    }

    private fun findClosingUnderscore(s: String, from: Int, end: Int): Int {
        var j = from
        while (j < end) {
            if (s[j] == '_' && j > from) {
                val boundary = j + 1 >= end || !s[j + 1].isLetterOrDigit()
                if (boundary) return j
            }
            j++
        }
        return -1
    }
}
