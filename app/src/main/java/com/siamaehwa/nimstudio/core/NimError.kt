package com.siamaehwa.nimstudio.core

class NimApiException(
    val status: Int,
    val title: String?,
    val detail: String?,
    val friendly: String,
) : Exception("HTTP $status ${title.orEmpty()} ${detail.orEmpty()}")

object NimErrors {

    fun friendlyFor(status: Int, title: String?, detail: String?): String {
        val d = detail.orEmpty()
        return when {
            status == 401 && d.contains("JWT", ignoreCase = true) ->
                "키 형식 오류(401): nvapi- 형식이 아닌 값이 전송된 것 같습니다. build.nvidia.com → API Keys에서 발급한 키를 그대로 붙여넣었는지 확인하세요."
            status == 401 ->
                "인증 실패(401): API 키가 승인되지 않았습니다. 키가 만료·삭제되지 않았는지 확인하고 다시 발급해 보세요."
            status == 403 ->
                "권한 없음(403): 키가 유효하지 않거나, 이 모델에 대한 접근 권한이 없습니다. build.nvidia.com에서 해당 모델 카드의 접근 가능 여부를 확인하세요."
            status == 404 ->
                "찾을 수 없음(404): 모델 ID 또는 엔드포인트가 올바르지 않습니다.${detailSuffix(d)}"
            status == 422 ->
                "요청 형식 오류(422): 모델이 파라미터를 거부했습니다.${detailSuffix(d)}"
            status == 429 ->
                "요청 한도 초과(429): 잠시 기다린 뒤 다시 시도하세요. 무료 크레딧 소진 시에도 이 오류가 납니다."
            status in 500..599 ->
                "서버 오류($status): NIM 서버가 일시적으로 불안정합니다. 잠시 후 재시도하세요."
            else -> {
                val base = title?.takeIf { it.isNotBlank() }?.let { "$it ($status)" } ?: "요청 실패 (HTTP $status)"
                base + detailSuffix(d)
            }
        }
    }

    fun friendlyFor(t: Throwable): String = when (t) {
        is NimApiException -> t.friendly
        is MissingApiKeyException -> "API 키가 설정되지 않았습니다. 설정 화면에서 키를 등록하세요."
        is java.net.UnknownHostException -> "네트워크를 찾을 수 없습니다. 인터넷 연결을 확인하세요."
        is java.net.SocketTimeoutException ->
            "모델 응답이 오랫동안 없어 연결을 끊었습니다. 잠시 후 다시 시도하거나 다른 모델을 선택하세요."
        is java.io.InterruptedIOException -> "연결이 끊겼습니다. 네트워크 상태를 확인하세요."
        is java.io.IOException -> "네트워크 오류: ${t.message ?: "연결에 실패했습니다"}"
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun detailSuffix(detail: String): String =
        if (detail.isBlank()) "" else " · $detail"
}
