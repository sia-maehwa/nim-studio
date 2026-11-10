package com.siamaehwa.nimstudio.core.agent

object AgentPaths {

    private val blockedNames = setOf(
        ".git",
        "build",
        ".gradle",
        "node_modules",
        ".idea",
        "dist",
        ".kotlin",
        ".cxx",
        "__pycache__",
        ".venv",
        "venv",
        "target",
        "Pods",
    )

    private val textExtensions = setOf(
        "kt", "kts", "java", "xml", "md", "txt", "json", "gradle", "properties",
        "yml", "yaml", "py", "js", "ts", "tsx", "jsx", "html", "css", "scss",
        "sh", "bash", "zsh", "toml", "ini", "cfg", "dart", "rs", "go", "c",
        "h", "cpp", "hpp", "swift", "sql", "pro", "env", "gitignore", "csv",
    )

    fun normalize(input: String): String? {
        var s = input.trim().replace('\\', '/')
        if (s.startsWith("/")) return null
        while (s.startsWith("./")) s = s.removePrefix("./")
        s = s.trimEnd('/')
        if (s == "." || s.isEmpty()) return ""
        val parts = s.split('/')
        if (parts.any { it == ".." || it == "." || it.isEmpty() }) return null
        return parts.joinToString("/")
    }

    fun isBlocked(rel: String): Boolean =
        rel.split('/').any { it in blockedNames }

    fun isTextFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (name in setOf(".gitignore", ".gitattributes", ".editorconfig", ".env")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in textExtensions
    }
}
