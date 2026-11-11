package com.siamaehwa.nimstudio.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object ClockInfo {
    private val formatter =
        DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREAN)

    fun todayLabel(): String = LocalDate.now().format(formatter)
}
