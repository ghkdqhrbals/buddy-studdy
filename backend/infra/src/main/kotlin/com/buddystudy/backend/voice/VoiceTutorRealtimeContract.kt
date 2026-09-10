package com.buddystudy.backend.voice

internal object VoiceTutorRealtimeContract {
    const val TURN_METADATA_KEY = "buddystudy_turn"
    const val RESPONSE_TOKEN_METADATA_KEY = "buddystudy_response_token"
    const val QUOTA_NOTICE_METADATA_KEY = "buddystudy_quota_notice"
    const val CONTINUOUS_INTERVENTION_TURN = "continuous_intervention"
    const val TUTOR_INTERVENTION_FIELD = "buddystudyTutorIntervention"
    const val QUOTA_EXHAUSTION_NOTICE_FIELD = "buddystudyQuotaExhaustionNotice"
    const val PLAYBACK_COMPLETED_EVENT = "buddystudy.voice.playback.completed"
    const val PLAYOUT_DRAINED_EVENT = "buddystudy.voice.playout.drained"
    const val SIDEBAND_READY_EVENT = "buddystudy.provider.sideband.ready"
    const val SPEECH_STARTED_EVENT = "buddystudy.voice.input.speech.started"
    const val SPEECH_STOPPED_EVENT = "buddystudy.voice.input.speech.stopped"
    const val INPUT_RETRY_EVENT = "buddystudy.voice.input.retry"
    // Server-only completion of silent native output: 0 = opening, positive = client acoustic sequence.
    const val INPUT_SETTLED_EVENT = "buddystudy.voice.input.settled"
    const val RESPONSE_INTERRUPTED_EVENT = "buddystudy.voice.response.interrupted"
    const val ABANDONED_RESPONSE_ID_FIELD = "abandonedResponseId"
    // Server-only lifecycle request. It is never accepted from or forwarded to the client/provider.
    const val SPOKEN_LESSON_END_EVENT = "buddystudy-internal-voice-session-end"
    const val STUDY_TREE_CHANGED_EVENT = "buddystudy.voice.study.changed"
    const val QUESTION_CHANGED_EVENT = "buddystudy.voice.question.changed"
    const val OPERATION_EVENT = "buddystudy.voice.operation"
    const val OPERATION_CONTEXT_EVENT = "buddystudy.voice.operation.context"
    const val USER_INPUT_REQUEST_EVENT = "buddystudy.voice.user_input.request"
    const val USER_INPUT_STATE_EVENT = "buddystudy.voice.user_input.state"
    const val USER_INPUT_SUBMIT_EVENT = "buddystudy.voice.user_input.submit"
    const val USER_INPUT_CANCEL_EVENT = "buddystudy.voice.user_input.cancel"
    const val USER_INPUT_PROTOCOL_HEADER = "X-Voice-User-Input-Protocol"
    const val USER_INPUT_PROTOCOL = "user-input-v1"
    const val ANSWER_STATE_EVENT = "buddystudy.voice.answer.state"
    const val SESSION_STATE_EVENT = "buddystudy.voice.session.state"
    val ANSWER_SESSION_PHASES = setOf("answering", "answer_finalizing", "answer_review", "answer_submitting", "answer_failed")
    val RECORD_SESSION_PHASES = setOf("question_ready", "question_reading", "grading", "graded", "grading_failed")
    val SESSION_PHASES = ANSWER_SESSION_PHASES + setOf("conversation", "question_loading", "question_generating", "question_reading",
        "question_ready", "grading", "graded", "ending", "ended", "question_failed", "grading_failed", "failed")
    const val ANSWER_TRANSCRIPT_EVENT = "buddystudy.voice.answer.transcript"
    const val ANSWER_FINISH_EVENT = "buddystudy.voice.answer.finish"
    const val ANSWER_SUBMIT_EVENT = "buddystudy.voice.answer.submit"
    const val ANSWER_CANCEL_EVENT = "buddystudy.voice.answer.cancel"
    const val ANSWER_SKIP_EVENT = "buddystudy.voice.answer.skip"
    const val STUDY_FOCUSED_EVENT = "buddystudy.voice.study.focused"
    const val TURN_PROTOCOL_HEADER = "X-Voice-Turn-Protocol"
    const val LOCAL_VAD_TURN_PROTOCOL = "local-vad-v1"
    const val REALTIME_NATIVE_TURN_PROTOCOL = "realtime-native-v1"
    const val PAUSE_PROTOCOL = "pause-v1"
    const val PAUSE_REQUEST_EVENT = "buddystudy.voice.pause.request"
    const val PAUSE_INPUT_QUIESCED_EVENT = "buddystudy.voice.pause.input.quiesced"
    const val RESUME_REQUEST_EVENT = "buddystudy.voice.resume.request"
    const val PAUSE_STATE_EVENT = "buddystudy.voice.pause.state"
}
