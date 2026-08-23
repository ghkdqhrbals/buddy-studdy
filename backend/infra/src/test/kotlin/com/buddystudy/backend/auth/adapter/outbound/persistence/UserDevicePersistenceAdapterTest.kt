package com.buddystudy.backend.auth.adapter.outbound.persistence

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class UserDevicePersistenceAdapterTest {
    @Test
    fun `active session count is converted to a boolean without relying on MySQL boolean row conversion`() = runBlocking {
        val repository = mock(UserDeviceRepository::class.java)
        val adapter = UserDevicePersistenceAdapter(repository)
        `when`(repository.countActiveSessions(3, "device-1")).thenReturn(1)
        `when`(repository.countActiveSessions(2, "device-1")).thenReturn(0)

        assertThat(adapter.hasActiveSession(3, "device-1")).isTrue()
        assertThat(adapter.hasActiveSession(2, "device-1")).isFalse()
    }
}
