package com.jagdeep.home.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CourseAnswer(val question: String, val options: List<String>, val answer: String? = null)

/** Local supplied answers only. No network lookup, AI inference or fuzzy matching. */
class CourseAnswerBank private constructor(private val modules: Map<String, List<CourseAnswer>>) {
    internal fun withAnswer(title: String, answer: CourseAnswer): CourseAnswerBank? {
        val name = modules.keys.singleOrNull { normalize(it) == normalize(title) } ?: title
        val key = normalize(answer.question) to answer.options.map(::optionText).sorted()
        val questions = modules[name].orEmpty().filterNot {
            (normalize(it.question) to it.options.map(::optionText).sorted()) == key
        } + answer
        return decode(Json.encodeToString(modules + (name to questions)))
    }
    internal fun module(title: String): List<CourseAnswer>? = modules.entries.singleOrNull {
        normalize(it.key) == normalize(title)
    }?.value

    internal fun answer(title: String, question: CourseQuestion): CourseOption? {
        val saved = entry(title, question) ?: return null
        val answer = saved.answer ?: return null
        return question.options.singleOrNull { optionText(it.text) == optionText(answer) && it.enabled && it.visible }
    }

    internal fun entry(title: String, question: CourseQuestion): CourseAnswer? {
        val actual = question.options.map { optionText(it.text) }
        if (actual.toSet().size != actual.size) return null
        return module(title)?.singleOrNull {
            normalize(it.question) == normalize(question.text) &&
                it.options.size == actual.size && it.options.map(::optionText).toSet() == actual.toSet()
        }
    }

    companion object {
        fun decode(text: String): CourseAnswerBank? = runCatching {
            require(text.length <= 2_000_000)
            val modules = Json.decodeFromString<Map<String, List<CourseAnswer>>>(text)
            require(modules.isNotEmpty() && modules.size <= 100)
            require(modules.keys.map(::normalize).toSet().size == modules.size)
            modules.forEach { (title, questions) ->
                require(title.isNotBlank() && questions.isNotEmpty() && questions.size <= 1000)
                questions.forEach { q ->
                    require(q.question.isNotBlank() && q.options.size in 2..20)
                    require(q.options.all { optionText(it).isNotBlank() })
                    q.answer?.let { answer ->
                        require(q.options.any { optionText(it) == optionText(answer) })
                    }
                }
            }
            val unique = modules.mapValues { (_, questions) ->
                questions.groupBy { normalize(it.question) to it.options.map(::optionText).sorted() }.values.map { copies ->
                    val answers = copies.mapNotNull { it.answer?.let(::optionText) }.toSet()
                    require(answers.size <= 1)
                    val saved = copies.firstOrNull { it.answer != null } ?: copies.first()
                    // Duplicate choices cannot identify one radio option. Keep the question for
                    // manual review without rejecting every other answer in the bank.
                    saved.takeIf { q ->
                        q.answer == null || q.options.count { optionText(it) == optionText(q.answer) } == 1
                    } ?: saved.copy(answer = null)
                }
            }
            CourseAnswerBank(unique)
        }.getOrNull()

        internal fun normalize(text: String) = text.replace('\u00a0', ' ')
            .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
            .replace(Regex("\\s+"), " ").trim().lowercase()

        /** Remove display-order labels without changing case or meaningful numbers such as 2.1. */
        internal fun optionLabel(text: String) = text.replace('\u00a0', ' ').trim()
            .replace(Regex("^\\d+[.)]\\s+"), "")

        internal fun optionText(text: String) = normalize(optionLabel(text))
    }
}

sealed interface AnswerBankSaveResult {
    data class Saved(val bank: CourseAnswerBank) : AnswerBankSaveResult
    data object Unavailable : AnswerBankSaveResult
    data object Conflict : AnswerBankSaveResult
    data object Invalid : AnswerBankSaveResult
}

interface CourseAnswerBankStore : com.jagdeep.shared.answers.AnswerBankFiles {
    fun forName(name: String): CourseAnswerBankStore = this
    suspend fun load(): CourseAnswerBank?
    suspend fun save(module: String, answer: CourseAnswer): AnswerBankSaveResult = AnswerBankSaveResult.Unavailable
    suspend fun saveReviewed(module: String, answers: List<ReviewedAnswer>): AnswerBankSaveResult = AnswerBankSaveResult.Unavailable
}
object NoCourseAnswerBankStore : CourseAnswerBankStore { override suspend fun load(): CourseAnswerBank? = null }

@Serializable
internal data class CourseOption(val id: String, val text: String, val selected: Boolean = false, val enabled: Boolean = true, val visible: Boolean = true)
@Serializable
internal data class CourseQuestion(val id: String, val text: String, val options: List<CourseOption>)
@Serializable
internal data class CourseAssessment(
    val module: String,
    val questions: List<CourseQuestion> = emptyList(),
    val result: String? = null,
    val next: String? = null,
    val submit: String? = null,
    val start: String? = null,
    val totalQuestions: Int? = null,
    val confirmSubmit: String? = null,
    val retry: String? = null,
)

enum class CourseApprovalKind { StartAssessment, SubmitAssessment, StartAssessmentPass }
data class CourseApprovalRequest(val token: String, val kind: CourseApprovalKind, val module: String)
data class ManualAnswerRequest(val token: String, val module: String, val question: String, val options: List<String>, val answer: String, val previousAnswer: String? = null)
data class AssessmentReviewQuestion(val id: String, val question: String, val options: List<String>, val selectedAnswer: String?, val bankAnswer: String?)
data class ReviewedAnswer(val answer: CourseAnswer, val previousAnswer: String?)
data class AssessmentFailureRequest(val token: String, val module: String, val canRetry: Boolean, val questions: List<AssessmentReviewQuestion> = emptyList())
enum class AssessmentAlertKind { Question, Failure, Stalled }
fun interface AssessmentAlerts { fun play(kind: AssessmentAlertKind) }
