package com.jagdeep.home.data

import com.jagdeep.home.domain.*
import com.jagdeep.shared.answers.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Installed app data, independent of its executable or working directory.
 *
 * [bundled] maps a bank file name to a reader for the copy shipped inside the app. A bundled bank is
 * written to [folder] only when no file of that name exists, so answers the user saved are never replaced.
 */
class DirectoryCourseAnswerBanks(
    private val folder: File,
    private val legacy: List<File> = emptyList(),
    private val bundled: Map<String, () -> String?> = emptyMap(),
    private val chooseFile: suspend () -> File? = { null },
    private val showFolder: suspend (File) -> Unit = {},
) : CourseAnswerBankStore {
    private val files = Mutex()
    override val available = true
    override val folderPath: String get() = folder.absolutePath

    private suspend fun prepare() = withContext(Dispatchers.IO) {
        files.withLock {
            folder.mkdirs()
            val target = File(folder, DEFAULT_ANSWER_BANK_FILE)
            if (!target.exists()) {
                val source = legacy.firstOrNull { runCatching { it.isFile && it.length() <= MAX_BYTES && CourseAnswerBank.decode(it.readText()) != null }.getOrDefault(false) }
                // Never overwrite a bank already placed in the permanent folder.
                if (source != null) Files.copy(source.toPath(), target.toPath())
            }
            bundled.forEach { (name, read) -> seed(name, read) }
        }
    }
    private fun seed(name: String, read: () -> String?) {
        if (answerBankFileName(name) != name) return
        val target = File(folder, name)
        if (target.exists()) return
        val text = runCatching(read).getOrNull() ?: return
        if (text.length > MAX_BYTES || CourseAnswerBank.decode(text) == null) return
        // Written beside the target and moved into place, so a crash never leaves a half-written bank.
        val partial = File(folder, "$name.tmp")
        partial.writeText(text)
        Files.move(partial.toPath(), target.toPath())
    }
    override fun forName(name: String): CourseAnswerBankStore {
        val fileName = answerBankFileName(name) ?: return NoCourseAnswerBankStore
        val delegate = FileCourseAnswerBankStore(listOf(File(folder, fileName)))
        return object : CourseAnswerBankStore {
            override suspend fun load(): CourseAnswerBank? { prepare(); return delegate.load() }
            override suspend fun save(module: String, answer: CourseAnswer): AnswerBankSaveResult { prepare(); return delegate.save(module,answer) }
            override suspend fun saveReviewed(module: String, answers: List<ReviewedAnswer>): AnswerBankSaveResult { prepare(); return delegate.saveReviewed(module,answers) }
        }
    }
    override suspend fun load() = forName(DEFAULT_ANSWER_BANK_FILE).load()
    override suspend fun save(module: String, answer: CourseAnswer) = forName(DEFAULT_ANSWER_BANK_FILE).save(module,answer)
    override suspend fun saveReviewed(module: String, answers: List<ReviewedAnswer>) = forName(DEFAULT_ANSWER_BANK_FILE).saveReviewed(module,answers)
    override suspend fun names(): List<String> = withContext(Dispatchers.IO) {
        prepare()
        folder.listFiles().orEmpty().filter { it.isFile && answerBankFileName(it.name) == it.name }.map { it.name }.sorted()
    }
    override suspend fun validate(name: String): String = withContext(Dispatchers.IO) {
        val normalized = answerBankFileName(name) ?: return@withContext "Use a simple file name ending in .json."
        prepare()
        val file = File(folder, normalized)
        when {
            !file.isFile -> "File not found. Put $normalized in the answer-bank folder or import it."
            file.length() > MAX_BYTES -> "The file is too large. Use a JSON file under 2 MB."
            forName(normalized).load() == null -> "Invalid answer bank. Check its questions, options and conflicting answers."
            else -> "$normalized is valid. It will be loaded when the routine starts; active assessments keep their current answers."
        }
    }
    override suspend fun importFile(name: String): String? {
        val normalized = answerBankFileName(name) ?: return "Use a simple file name ending in .json."
        val source = chooseFile() ?: return null
        return withContext(Dispatchers.IO) {
            prepare()
            files.withLock {
                val target = File(folder,normalized)
                when {
                    target.exists() -> "That name already exists. Choose another name; the existing bank was not changed."
                    !source.isFile || source.length() > MAX_BYTES || CourseAnswerBank.decode(source.readText()) == null -> "The selected file is not a valid answer bank under 2 MB."
                    else -> { Files.copy(source.toPath(),target.toPath()); "Imported $normalized. Choose this file in the routine options." }
                }
            }
        }
    }
    override suspend fun openFolder(): String? { prepare(); showFolder(folder); return null }
    private companion object { const val MAX_BYTES = 2_000_000L }
}
