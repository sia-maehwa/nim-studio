package com.siamaehwa.nimstudio

import com.siamaehwa.nimstudio.core.InlineMarkdown
import com.siamaehwa.nimstudio.core.MdInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkdownTest {

    @Test
    fun boldIsParsed() {
        val tokens = InlineMarkdown.parse("**굵게**")
        assertEquals(1, tokens.size)
        val bold = tokens[0] as MdInline.Bold
        assertEquals("굵게", (bold.children[0] as MdInline.Text).text)
    }

    @Test
    fun italicIsParsed() {
        val tokens = InlineMarkdown.parse("앞 *기울임* 뒤")
        assertEquals(3, tokens.size)
        assertTrue(tokens[0] is MdInline.Text)
        assertTrue(tokens[1] is MdInline.Italic)
        assertTrue(tokens[2] is MdInline.Text)
    }

    @Test
    fun underscoreItalicWithInnerUnderscore() {
        val tokens = InlineMarkdown.parse("_snake_case_ 변수")
        assertTrue(tokens[0] is MdInline.Italic)
        val inner = (tokens[0] as MdInline.Italic).children[0] as MdInline.Text
        assertEquals("snake_case", inner.text)
    }

    @Test
    fun inlineCodeIsParsed() {
        val tokens = InlineMarkdown.parse("`val x = 1` 실행")
        val code = tokens[0] as MdInline.Code
        assertEquals("val x = 1", code.text)
    }

    @Test
    fun strikeIsParsed() {
        val tokens = InlineMarkdown.parse("~~지움~~")
        assertTrue(tokens[0] is MdInline.Strike)
    }

    @Test
    fun linkIsParsed() {
        val tokens = InlineMarkdown.parse("[NIM](https://build.nvidia.com)")
        val link = tokens[0] as MdInline.Link
        assertEquals("NIM", link.text)
        assertEquals("https://build.nvidia.com", link.url)
    }

    @Test
    fun nestedBoldItalic() {
        val tokens = InlineMarkdown.parse("**a *b* c**")
        val bold = tokens[0] as MdInline.Bold
        assertEquals(3, bold.children.size)
        assertTrue(bold.children[1] is MdInline.Italic)
    }

    @Test
    fun unmatchedDelimitersStayLiteral() {
        val tokens = InlineMarkdown.parse("**닫히지 않음")
        assertEquals(1, tokens.size)
        assertEquals("**닫히지 않음", (tokens[0] as MdInline.Text).text)
    }

    @Test
    fun escapedDelimitersStayLiteral() {
        val tokens = InlineMarkdown.parse("\\*별표\\*")
        assertEquals(1, tokens.size)
        assertEquals("*별표*", (tokens[0] as MdInline.Text).text)
    }

    @Test
    fun plainTextSingleToken() {
        val tokens = InlineMarkdown.parse("그냥 문장입니다.")
        assertEquals(1, tokens.size)
        assertEquals("그냥 문장입니다.", (tokens[0] as MdInline.Text).text)
    }
}
