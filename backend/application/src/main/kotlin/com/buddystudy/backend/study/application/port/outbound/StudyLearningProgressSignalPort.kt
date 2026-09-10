package com.buddystudy.backend.study.application.port.outbound

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Wake-up hints only. The caller must re-read and authorize the canonical process state. */
interface StudyLearningProgressSignalPort {
    /** Publish only after the current transaction commits; delivery is best effort. */
    suspend fun changed(correlationId: String)

    /** Subscription-ready/reconnected, matching changes and slow reconciliation all request a fresh read. */
    fun changes(correlationId: String): Flow<Unit>
}

object NoopStudyLearningProgressSignalPort : StudyLearningProgressSignalPort {
    override suspend fun changed(correlationId: String) = Unit
    override fun changes(correlationId: String): Flow<Unit> = flow {
        emit(Unit)
        awaitCancellation()
    }
}
