package com.siamaehwa.nimstudio.core.web

import java.io.IOException
import java.net.ConnectException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class WebResult(
    val title: String,
    val url: String,
    val snippet: String,
)

object WebSearch {
    private const val MAX_RESULTS = 6
    private const val ENDPOINT = "https://lite.duckduckgo.com/lite/"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val timeSensitiveWords = listOf(
        "오늘", "어제", "내일", "최신", "최근", "현재", "지금", "근황", "뉴스",
        "시사", "속보", "날씨", "주가", "환율", "일정", "결과", "순위", "출시",
        "업데이트", "가격", "누가", "당선", "선거", "올해", "내년", "작년",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val linkRegexes = listOf(
        Regex("""href=['"]([^'"]+)['"][^>]*class=['"]result-link['"]>([^<]*)</a>"""),
        Regex("""class=['"]result-link['"][^>]*href=['"]([^'"]+)['"]>([^<]*)</a>"""),
    )
    private val snippetRegex =
        Regex("""class=['"]result-snippet['"][^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)

    fun looksTimeSensitive(query: String): Boolean {
        val lower = query.lowercase()
        if (timeSensitiveWords.any { lower.contains(it) }) return true
        return Regex("""\b(19|20)\d{2}\b""").containsMatchIn(lower)
    }

    suspend fun search(query: String): List<WebResult> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("$ENDPOINT?q=$encoded")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("검색 실패 (HTTP ${response.code})")
                parse(response.body?.string().orEmpty())
            }
        } catch (e: ConnectException) {
            throw IOException("웹 검색 서버에 연결할 수 없습니다", e)
        }
    }

    fun format(results: List<WebResult>, query: String): String = buildString {
        append("[웹 검색 결과: ").append(query).append("]\n")
        results.forEachIndexed { index, result ->
            append(index + 1).append(". ").append(result.title).append('\n')
            if (result.snippet.isNotBlank()) {
                append("   ").append(result.snippet.take(300)).append('\n')
            }
            append("   출처: ").append(result.url).append('\n')
        }
        append("위 결과를 참고해 최신 정보를 반영해서 답하세요. 출처가 있으면 함께 알려주세요.")
    }

    private fun parse(html: String): List<WebResult> {
        val results = mutableListOf<WebResult>()
        var title = ""
        var url = ""
        var snippet = ""

        fun flush() {
            if (url.isNotBlank() && results.size < MAX_RESULTS) {
                results.add(
                    WebResult(
                        title = title.ifBlank { url },
                        url = url,
                        snippet = snippet.trim(),
                    ),
                )
            }
            title = ""
            url = ""
            snippet = ""
        }

        for (row in html.split("<tr")) {
            val link = linkRegexes.firstNotNullOfOrNull { it.find(row) }
            if (link != null) {
                flush()
                url = resolveUrl(decodeEntities(link.groupValues[1]))
                title = stripTags(link.groupValues[2]).trim()
                continue
            }
            val snip = snippetRegex.find(row)
            if (snip != null && url.isNotBlank() && snippet.isBlank()) {
                snippet = stripTags(decodeEntities(snip.groupValues[1]))
            }
        }
        flush()
        return results.take(MAX_RESULTS)
    }

    private fun resolveUrl(raw: String): String {
        if (!raw.contains("uddg=")) return raw
        val query = raw.substringAfter("?", "").substringBefore("#")
        return query.split('&')
            .firstOrNull { it.startsWith("uddg=") }
            ?.removePrefix("uddg=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: raw
    }

    private fun decodeEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")

    private fun stripTags(text: String): String = text.replace(Regex("<[^>]+>"), "")
}
