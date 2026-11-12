package com.siamaehwa.nimstudio.core.agent

import java.io.File
import java.util.concurrent.TimeUnit

data class ShellResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
)

object ShellRunner {
    private const val MAX_OUTPUT_CHARS = 20_000

    fun run(
        directory: String,
        command: String,
        timeoutSeconds: Long = 60,
    ): ShellResult {
        val dir = File(directory)
        if (!dir.isDirectory) {
            return ShellResult(-1, "작업 폴더를 찾을 수 없습니다: $directory", timedOut = false)
        }
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { lines ->
                    lines.forEachLine { line ->
                        if (output.length < MAX_OUTPUT_CHARS) {
                            output.append(line).append('\n')
                        }
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1000)
            return ShellResult(
                exitCode = -1,
                output = output.toString().take(MAX_OUTPUT_CHARS) +
                    "\n[시간 초과: ${timeoutSeconds}초 후 중단했습니다]",
                timedOut = true,
            )
        }
        reader.join(2000)
        val text = output.toString()
        return ShellResult(
            exitCode = process.exitValue(),
            output = if (text.length > MAX_OUTPUT_CHARS) {
                text.take(MAX_OUTPUT_CHARS) + "\n[출력이 길어 잘렸습니다]"
            } else {
                text
            },
            timedOut = false,
        )
    }
}
