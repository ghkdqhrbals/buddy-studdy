package com.buddystudy.backend.study.adapter.inbound.stream

import kotlinx.coroutines.runBlocking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PushDeepLinkFactoryTest {
    @Test
    fun `question push deep link opens the record identity even when study id exists`(): Unit = runBlocking {
        assertThat(PushDeepLinkFactory.studyRoomOrRecord(studyId = "77", recordId = "10"))
            .isEqualTo("buddystudy://records/10")
    }

    @Test
    fun `question push deep link uses record detail for legacy payloads`(): Unit = runBlocking {
        assertThat(PushDeepLinkFactory.studyRoomOrRecord(studyId = null, recordId = "10"))
            .isEqualTo("buddystudy://records/10")
    }
}
