package com.jagdeep.shared.answers

const val DEFAULT_ANSWER_BANK_FILE = "sagarmeinyog.json"

/** A single local filename, never an arbitrary path from an imported routine. */
fun answerBankFileName(value: String): String? {
    val name = value.trim().let { if (it.endsWith(".json", true)) it else "$it.json" }
    return name.takeIf { it.length <= 120 && Regex("[A-Za-z0-9][A-Za-z0-9 _.-]*\\.json", RegexOption.IGNORE_CASE).matches(it) && !it.contains("..") }
}

/** Compact cross-feature contract for the user's local answer-bank folder. */
interface AnswerBankFiles {
    val available: Boolean get() = false
    val folderPath: String? get() = null
    suspend fun names(): List<String> = emptyList()
    suspend fun validate(name: String): String = "Answer-bank files are available on desktop."
    suspend fun importFile(name: String): String? = "Answer-bank files are available on desktop."
    suspend fun openFolder(): String? = "Answer-bank files are available on desktop."
}
object NoAnswerBankFiles : AnswerBankFiles
