package com.buddystudy.backend.study.adapter.stream

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PushDeepLinkFactoryTest {
    @Test
    fun `question push deep link opens the record identity`() {
        assertThat(PushDeepLinkFactory.studyRoomOrRecord(recordId = "10"))
            .isEqualTo("buddystudy://records/10")
    }

    @Test
    fun `question push deep link supports non-numeric record identities`() {
        assertThat(PushDeepLinkFactory.studyRoomOrRecord(recordId = "record-10"))
            .isEqualTo("buddystudy://records/record-10")
    }
}
