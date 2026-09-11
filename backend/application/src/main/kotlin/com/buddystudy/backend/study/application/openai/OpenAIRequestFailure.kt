package com.buddystudy.backend.study.application.openai

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.fasterxml.jackson.core.JsonProcessingException
import kotlinx.coroutines.CancellationException
import org.springframework.dao.TransientDataAccessException
import org.springframework.web.client.HttpStatusCodeException
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/** An adapter may preserve exact provider retry metadata without exposing its
 * SDK or response body to application orchestration. */
class OpenAIRequestFailure(val retryable: Boolean, cause: Throwable) :
    RuntimeException("OpenAI request failed.", cause)

object OpenAIRequestRetryPolicy {
    fun isRetryable(error: Throwable): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val causes = generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.take(16).toList()
        if (causes.any { it is CancellationException }) return false
        causes.filterIsInstance<OpenAIRequestFailure>().firstOrNull()?.let { return it.retryable }
        causes.filterIsInstance<ApiException>().firstOrNull()?.let {
            return it.code != ApiErrorCode.VOICE_TUTOR_PROVIDER_QUOTA_EXHAUSTED && retryableStatus(it.status.value())
        }
        causes.filterIsInstance<HttpStatusCodeException>().firstOrNull()?.let { return retryableStatus(it.statusCode.value()) }
        if (causes.any { it is JsonProcessingException }) return false
        return causes.any { it is IOException || it is TransientDataAccessException }
    }

    private fun retryableStatus(status: Int) = status == 408 || status == 429 || status in 500..599
}
