package com.doro.terminal.shell.linux

internal fun summarizeInstallFailure(message: String, timedOut: Boolean): String {
    val lines = message.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
    if (lines.isEmpty()) return if (timedOut) "看门狗已中止任务" else "安装失败"

    val errorMarkers = listOf(
        "error",
        "failed",
        "not found",
        "no space",
        "cannot",
        "denied",
        "超时",
        "无输出",
        "失败",
        "缺少",
    )
    val important = lines.filter { line ->
        errorMarkers.any { marker -> line.contains(marker, ignoreCase = true) }
    }.ifEmpty { lines.takeLast(3) }
    val summary = important.takeLast(3).joinToString("\n")
    return if (timedOut && !summary.contains("超时") && !summary.contains("看门狗")) {
        "看门狗已中止任务\n$summary"
    } else {
        summary
    }.take(480)
}
