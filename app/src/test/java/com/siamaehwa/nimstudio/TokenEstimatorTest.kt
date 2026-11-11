package com.siamaehwa.nimstudio

import com.siamaehwa.nimstudio.core.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun emptyTextIsZero() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun koreanCountsAboutOnePerChar() {
        assertEquals(5, TokenEstimator.estimate("안녕하세요"))
    }

    @Test
    fun asciiCountsAboutQuarterPerChar() {
        assertEquals(2, TokenEstimator.estimate("hello"))
    }

    @Test
    fun longerTextHasMoreTokens() {
        val short = TokenEstimator.estimate("hello")
        val long = TokenEstimator.estimate("hello world, this is a longer sentence")
        assertTrue(long > short)
    }
}
