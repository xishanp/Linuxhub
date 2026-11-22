package com.doro.terminal.shell.linux

internal data class VendorAppTask(
    val key: String,
    val label: String,
    val packageFiles: List<String>,
)

internal object VendorAppTasks {
    fun parse(text: String): List<VendorAppTask> {
        val tasks = text.lineSequence().mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@mapIndexedNotNull null
            val columns = line.split('\t', limit = 3)
            require(columns.size == 3) { "应用任务清单第 ${index + 1} 行格式错误" }
            val key = columns[0].trim()
            val label = columns[1].trim()
            val files = columns[2].trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            require(key.matches(Regex("[a-z0-9][a-z0-9-]*"))) {
                "应用任务标识非法：$key"
            }
            require(label.isNotBlank() && '\n' !in label && '\r' !in label) {
                "应用任务名称非法：$label"
            }
            require(files.isNotEmpty()) { "应用任务缺少安装包：$label" }
            files.forEach(::requirePackageFile)
            VendorAppTask(key, label, files)
        }.toList()
        require(tasks.isNotEmpty()) { "应用任务清单为空" }
        require(tasks.map(VendorAppTask::key).distinct().size == tasks.size) {
            "应用任务标识重复"
        }
        return tasks
    }

    fun parseDependencies(text: String): List<String> {
        val files = text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .onEach(::requirePackageFile)
            .toList()
        require(files.isNotEmpty()) { "应用依赖清单为空" }
        require(files.distinct().size == files.size) { "应用依赖清单包含重复包" }
        return files
    }

    private fun requirePackageFile(value: String) {
        require(
            value.endsWith(".deb") &&
                value.none { it == '/' || it == '\\' || it == '\u0000' || it.isWhitespace() } &&
                value != "." && value != ".."
        ) { "应用安装包文件名非法：$value" }
    }
}
