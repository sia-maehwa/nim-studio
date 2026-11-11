package com.siamaehwa.nimstudio.core

enum class ReasoningEffort(val id: String?, val label: String) {
    Default(null, "기본"),
    None("none", "없음"),
    Low("low", "낮음"),
    Medium("medium", "보통"),
    High("high", "높음"),
    XHigh("xhigh", "매우 높음"),
    Max("max", "최대"),
    ;

    companion object {
        fun fromId(id: String?): ReasoningEffort =
            entries.firstOrNull { it.id == id } ?: Default
    }
}
