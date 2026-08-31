package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.mono
import reactor.core.publisher.Mono

/**
 * Owned by the sideband lifecycle, independently of its ordered receive loop.
 * A slow classifier cannot hide provider audio completion or stop native RTP.
 * The controller correlates every asynchronous result with its original batch.
 */
internal fun voiceTutorInputAssessmentRelay(
    controller: VoiceTutorDuplexTurnController,
    userId: Long,
    language: String,
    assessment: VoiceTutorInputAssessmentUseCase,
    onProviderEvent: suspend (String, Boolean, Boolean) -> Unit,
): Mono<Void> = controller.inputActions().concatMap { action ->
    mono {
        when (action) {
            is VoiceTutorInputTurnCoordinator.Action.Assess -> {
                // A callback can be handed off before close/expiry and start
                // later on a coroutine worker. Revalidate before a paid request.
                if (!controller.canAssessInput(action.token)) return@mono
                val result = try {
                    Result.success(
                        assessment.assess(
                            VoiceTutorInputAssessmentRequest(
                                userId = userId,
                                language = language,
                                teacherContext = action.teacherContext,
                                utterances = action.utterances,
                            ),
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
                controller.completeInputAssessment(action.token, result)
            }
            is VoiceTutorInputTurnCoordinator.Action.Publish -> {
                // The exact accepted ASR is persisted before it can release a
                // tutor response. Never rephrase it or replay a rejected item.
                if (controller.canPublishInput(action.itemId)) {
                    onProviderEvent(action.rawEvent, true, true)
                    controller.confirmInputPublished(action.itemId)
                }
            }
            is VoiceTutorInputTurnCoordinator.Action.Retry -> {
                if (controller.acceptsInputEvents()) {
                    onProviderEvent(
                        JsonMapperProvider.mapper.writeValueAsString(
                            mapOf("type" to VoiceTutorRealtimeContract.INPUT_RETRY_EVENT),
                        ),
                        false,
                        true,
                    )
                }
            }
            // Only the controller may delete exact committed USER items or
            // release response creation under its turn/lifecycle lock.
            is VoiceTutorInputTurnCoordinator.Action.Delete,
            is VoiceTutorInputTurnCoordinator.Action.Ready,
            -> error("Invalid Voice Tutor input worker action.")
        }
    }.then()
}.then()
