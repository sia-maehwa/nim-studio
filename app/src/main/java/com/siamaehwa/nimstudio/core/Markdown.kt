package com.siamaehwa.nimstudio.core

sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
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
