package com.buddystudy.backend.study.adapter.inbound.stream

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PushDeepLinkFactoryTest {
    @Test
    fun `question push deep link opens the record identity even when study id exists`() {
        assertThat(PushDeepLinkFactory.studyRoomOrRecord(studyId = "77", recordId = "10"))
            .isEqualTo("buddystudy://records/10")
    }

    @Test
    fun `question push deep link uses record detail for legacy payloads`() {
        assertThat(PushDeepLinkFactory.studyRoomOrRecord(studyId = null, recordId = "10"))
            .isEqualTo("buddystudy://records/10")
    }
}
