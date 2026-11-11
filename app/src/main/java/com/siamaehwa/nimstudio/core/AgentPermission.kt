package com.siamaehwa.nimstudio.core

enum class AgentPermission(val id: String, val label: String, val description: String) {
    ReadOnly(
        id = "read_only",
        label = "읽기 전용",
        description = "파일 읽기·검색만 허용합니다. 쓰기/수정과 플러그인 실행은 차단됩니다.",
    ),
    AutoReview(
        id = "auto_review",
        label = "자동 검토",
        description = "쓰기/수정·플러그인 실행 전에 확인 카드를 띄웁니다.",
    ),
    Full(
        id = "full",
        label = "전체 허용",
        description = "모든 도구를 확인 없이 실행합니다.",
    ),
    ;

    companion object {
        fun fromId(id: String?): AgentPermission =
            entries.firstOrNull { it.id == id } ?: AutoReview
    }
}
