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

private fun toolError(code: String, message: String) = VoiceTutorMcpToolResult(
    output = JsonMapperProvider.mapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))),
    isError = true,
)
