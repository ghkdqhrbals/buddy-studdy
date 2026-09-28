package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.common.application.model.RecordTimeDisplayPolicy
import com.buddystudy.backend.community.application.model.CommunityQuestionResponse
import com.buddystudy.backend.community.application.model.CommunityQuestionsResponse
import com.buddystudy.backend.study.application.model.QuestionThreadResponse
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyLearningRecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import java.time.Instant

/** Creates v2 response copies, leaving cached/use-case and v1 DTOs untouched. */
internal class RecordTimeDisplayMapper(language: String, now: Instant = Instant.now()) {
    private val policy = RecordTimeDisplayPolicy(language, now)

    fun record(record: StudyRecordResponse) = record.copy(
        timeDisplay = policy.display(record.answeredAt, record.question.createdAt),
    )

    fun records(page: RecordsPageResponse) = page.copy(records = page.records.map(::record))

    fun study(study: StudyRoomResponse) = study.copy(
        pendingQuestion = study.pendingQuestion?.let(::record),
        latestQuestion = study.latestQuestion?.let(::record),
    )

    fun studies(page: StudyPageResponse) = page.copy(studies = page.studies.map(::study))

    fun thread(thread: QuestionThreadResponse) = thread.copy(records = thread.records.map(::record))

    fun learningRecords(page: StudyLearningRecordsPageResponse) = page.copy(items = page.items.map { item ->
        item.copy(record = item.record?.let(::record), questionRecord = item.questionRecord?.let(::record))
    })

    fun publicQuestion(question: CommunityQuestionResponse) = question.copy(
        timeDisplay = policy.display(question.answeredAt, question.createdAt),
    )

    fun publicQuestions(page: CommunityQuestionsResponse) = page.copy(
        questions = page.questions.map(::publicQuestion),
        items = page.items.map { item -> item.question?.let { item.copy(question = publicQuestion(it)) } ?: item },
    )
}
