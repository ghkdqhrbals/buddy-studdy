package com.buddystuddy.backend.study.adapter.inbound.stream

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PushDeepLinkFactoryTest {
    @Test
    fun `question push deep link opens the study room when study id exists`() {
        assertThat(PushDeepLinkFactory.studyRoomOrRecord(studyId = "77", recordId = "10"))
            .isEqualTo("buddystuddy://studies/77")
    }

    @Test
    fun `question push deep link falls back to record detail for legacy payloads`() {
        assertThat(PushDeepLinkFactory.studyRoomOrRecord(studyId = null, recordId = "10"))
            .isEqualTo("buddystuddy://records/10")
    }
}
