package com.jagdeep.home.data

import com.jagdeep.home.domain.CourseAnswerBank
import com.jagdeep.home.domain.CourseAnswerBankStore
import com.jagdeep.home.domain.CourseAnswer
import com.jagdeep.home.domain.ReviewedAnswer
import com.jagdeep.home.domain.AnswerBankSaveResult
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileCourseAnswerBankStore(private val candidates: List<File>) : CourseAnswerBankStore {
    private val writes = Mutex()
    override suspend fun load(): CourseAnswerBank? = withContext(Dispatchers.IO) {
        val file = candidates.firstOrNull { it.isFile } ?: return@withContext null
        runCatching {
            if (file.length() > 2_000_000) return@withContext null
            CourseAnswerBank.decode(file.readText())
        }.getOrNull()
    }

    override suspend fun save(module: String, answer: CourseAnswer): AnswerBankSaveResult = write(module, listOf(ReviewedAnswer(answer, null)), false)

    override suspend fun saveReviewed(module: String, answers: List<ReviewedAnswer>): AnswerBankSaveResult = write(module, answers, true)

    private suspend fun write(module: String, answers: List<ReviewedAnswer>, reviewed: Boolean): AnswerBankSaveResult = writes.withLock {
        withContext(Dispatchers.IO) {
            if (answers.isEmpty() || answers.any { it.answer.answer == null } ||
                CourseAnswerBank.decode(Json.encodeToString(mapOf(module to answers.map { it.answer }))) == null)
                return@withContext AnswerBankSaveResult.Invalid
            val file = candidates.firstOrNull { it.isFile } ?: candidates.firstOrNull()
                ?: return@withContext AnswerBankSaveResult.Unavailable
            runCatching {
                val target = file.toPath().toAbsolutePath()
                Files.createDirectories(target.parent)
                FileChannel.open(target.resolveSibling(target.fileName.toString() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    channel.lock().use {
                        val original = if (Files.exists(target)) {
                            if (Files.size(target) > 2_000_000) return@withContext AnswerBankSaveResult.Invalid
                            Files.readString(target)
                        } else null
                        if (original != null && CourseAnswerBank.decode(original) == null) return@withContext AnswerBankSaveResult.Invalid
                        val modules = original?.let { Json.decodeFromString<Map<String, List<CourseAnswer>>>(it) }.orEmpty().toMutableMap()
                        val name = modules.keys.singleOrNull { CourseAnswerBank.normalize(it) == CourseAnswerBank.normalize(module) } ?: module
                        fun key(value: CourseAnswer) = CourseAnswerBank.normalize(value.question) to value.options.map(CourseAnswerBank::optionText).sorted()
                        for (review in answers) {
                            val answer = review.answer
                            val existing = modules[name].orEmpty()
                            val matches = existing.filter { key(it) == key(answer) }
                            fun normalized(value: String?) = value?.let(CourseAnswerBank::optionText)
                            if (matches.any {
                                if (reviewed) normalized(it.answer) != normalized(review.previousAnswer)
                                else it.answer != null && normalized(it.answer) != normalized(answer.answer)
                            } || (reviewed && matches.isEmpty() && review.previousAnswer != null)) {
                                return@withContext AnswerBankSaveResult.Conflict
                            }
                            modules[name] = existing.filterNot { key(it) == key(answer) } + answer
                        }
                        val encoded = Json { prettyPrint = true }.encodeToString(modules) + "\n"
                        val bank = CourseAnswerBank.decode(encoded) ?: return@withContext AnswerBankSaveResult.Invalid
                        if (encoded.toByteArray().size > 2_000_000) return@withContext AnswerBankSaveResult.Invalid
                        val temporary = Files.createTempFile(target.parent, ".answer-bank-", ".tmp")
                        try {
                            Files.writeString(temporary, encoded)
                            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
                            // Do not replace edits made by another writer while preparing this update.
                            val current = if (Files.exists(target)) Files.readString(target) else null
                            if (current != original) return@withContext AnswerBankSaveResult.Conflict
                            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } finally { Files.deleteIfExists(temporary) }
                        AnswerBankSaveResult.Saved(bank)
                    }
                }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                AnswerBankSaveResult.Unavailable
            }
        }
    }
}
