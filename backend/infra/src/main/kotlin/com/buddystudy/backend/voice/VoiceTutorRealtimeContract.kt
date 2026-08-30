package com.buddystudy.backend.voice

internal object VoiceTutorRealtimeContract {
    const val TURN_METADATA_KEY = "buddystudy_turn"
    const val RESPONSE_TOKEN_METADATA_KEY = "buddystudy_response_token"
    const val CONTINUOUS_INTERVENTION_TURN = "continuous_intervention"
    const val TUTOR_INTERVENTION_FIELD = "buddystudyTutorIntervention"
    const val PLAYBACK_COMPLETED_EVENT = "buddystudy.voice.playback.completed"
    const val PLAYOUT_DRAINED_EVENT = "buddystudy.voice.playout.drained"
    const val SIDEBAND_READY_EVENT = "buddystudy.provider.sideband.ready"
}
