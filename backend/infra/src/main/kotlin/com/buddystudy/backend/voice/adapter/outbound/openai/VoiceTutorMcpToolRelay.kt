package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyChangeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withTimeout
import reactor.core.publisher.Mono

/** Serial per-call MCP execution, independently of audio/provider receive. */
internal fun voiceTutorMcpToolRelay(
    controller: VoiceTutorDuplexTurnController,
    context: VoiceTutorWebRtcControlContext,
    tools: VoiceTutorMcpToolPort,
    onProviderEvent: suspend (String, Boolean, Boolean) -> Unit,
    executionTimeoutMillis: Long = 15_000,
): Mono<Void> = controller.toolActions().concatMap({ call ->
    mono {
        if (!controller.beginToolExecution(call.callId)) return@mono
        val result = if (call.arguments == null) {
            toolError("INVALID_ARGUMENTS", "Tool arguments must be a bounded JSON object.")
        } else {
            try {
                withTimeout(executionTimeoutMillis) {
                    tools.execute(context.copy(dialogueBoundary = controller.mutationDialogueBoundary()), call.name, call.arguments)
                }
            } catch (_: TimeoutCancellationException) {
                // A timed-out write may have committed. Never automatically
                // replay it or tell the learner it definitely failed/succeeded.
                toolError("TOOL_TIMEOUT", "The result is not confirmed; read saved topics before retrying a write.")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                toolError("TOOL_UNAVAILABLE", "The result is not confirmed; read saved topics before retrying a write.")
            }
        }
        if (!controller.completeToolExecution(call.callId, result)) return@mono
        val studyId = (result.changedStudyId ?: result.createdStudyId)?.takeIf { it > 0 }
        val kind = result.changeKind ?: result.createdStudyId?.let { VoiceTutorStudyChangeKind.CREATED }
        val validDeletion = kind != VoiceTutorStudyChangeKind.DELETED ||
            (result.deletedStudyIds.size in 1..128 && studyId in result.deletedStudyIds &&
                result.deletedStudyIds.all { it > 0 } && result.deletedStudyIds.distinct().size == result.deletedStudyIds.size)
        if (controller.acceptsInputEvents()) {
            voiceTutorLessonFocusEvent(result)?.let { onProviderEvent(it, false, true) }
        }
        if (!result.isError && result.studyTreeChanged && studyId != null && kind != null && validDeletion && controller.acceptsInputEvents()) {
            onProviderEvent(
                JsonMapperProvider.mapper.writeValueAsString(
                    mapOf(
                        "type" to VoiceTutorRealtimeContract.STUDY_TREE_CHANGED_EVENT,
                        "studyId" to studyId, "change" to kind.name.lowercase(),
                        "deletedStudyIds" to result.deletedStudyIds,
                    ),
                ),
                false,
                true,
            )
        }
    }.then()
}, 1).then()

/** Only typed, server-confirmed metadata reaches the UI; tool JSON is never an event authority. */
internal fun voiceTutorLessonFocusEvent(result: VoiceTutorMcpToolResult): String? {
    if (result.isError) return null
    val focus = result.lessonFocus
    if (focus != null && focus.studyId > 0 && focus.revision > 0 &&
        focus.snapshot.studyId == focus.studyId && result.lessonRevision == focus.revision &&
        focus.snapshot.topic.isNotBlank() && focus.snapshot.topic.length <= 255 &&
        focus.snapshot.difficulty in 1..10 && focus.snapshot.parentStudyId?.let { it > 0 } != false
    ) return JsonMapperProvider.mapper.writeValueAsString(mapOf(
        "type" to VoiceTutorRealtimeContract.STUDY_FOCUSED_EVENT,
        "focus" to mapOf(
            "studyId" to focus.studyId, "parentStudyId" to focus.snapshot.parentStudyId,
            "topic" to focus.snapshot.topic, "difficulty" to focus.snapshot.difficulty,
            "revision" to focus.revision,
        ),
    ))
    val deletedIds = result.deletedStudyIds
    if (result.lessonFocusCleared && focus == null && result.studyTreeChanged &&
        result.changeKind == VoiceTutorStudyChangeKind.DELETED && result.changedStudyId in deletedIds &&
        deletedIds.size in 1..128 && deletedIds.all { it > 0 } && deletedIds.distinct().size == deletedIds.size
    ) return JsonMapperProvider.mapper.writeValueAsString(mapOf(
        "type" to VoiceTutorRealtimeContract.STUDY_FOCUSED_EVENT, "focus" to null,
    ))
    return null
}

private fun toolError(code: String, message: String) = VoiceTutorMcpToolResult(
    output = JsonMapperProvider.mapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))),
    isError = true,
)
