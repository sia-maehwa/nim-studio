package com.siamaehwa.nimstudio

import com.siamaehwa.nimstudio.core.MarkdownParser
import com.siamaehwa.nimstudio.core.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun plainTextBecomesSingleParagraph() {
        val blocks = MarkdownParser.parse("안녕하세요\n두 번째 줄")
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MdBlock.Paragraph
        assertEquals("안녕하세요\n두 번째 줄", paragraph.text)
    }

    @Test
    fun codeFenceWithLanguage() {
        val source = "설명\n```kotlin\nval x = 1\nprintln(x)\n```\n끝"
        val blocks = MarkdownParser.parse(source)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        val code = blocks[1] as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.code)
        assertTrue(blocks[2] is MdBlock.Paragraph)
    }

    @Test
    fun unclosedFenceStillYieldsCodeBlock() {
        val blocks = MarkdownParser.parse("```\nline1\nline2")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertEquals("line1\nline2", code.code)
    }

    @Test
    fun emptySourceYieldsNoBlocks() {
        val blocks = MarkdownParser.parse("")
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun paragraphBlankLinesTrimmedAtEdges() {
        val blocks = MarkdownParser.parse("\n\nhello\n\n\nworld\n")
        assertEquals(1, blocks.size)
        assertEquals("hello\n\n\nworld", (blocks[0] as MdBlock.Paragraph).text)
    }
}
