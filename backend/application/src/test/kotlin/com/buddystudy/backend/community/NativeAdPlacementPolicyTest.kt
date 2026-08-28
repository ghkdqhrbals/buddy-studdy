package com.buddystudy.backend.community

import com.buddystudy.backend.community.application.policy.NativeAdPlacementPolicy
import com.buddystudy.community.domain.entity.NativeAdPlacementPolicyEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class NativeAdPlacementPolicyTest {
    private val now = Instant.parse("2026-08-25T00:00:00Z")

    @Test
    fun `repeat gap accepts disabled or at least one minute and rejects intermediate values`() {
        assertThat(NativeAdPlacementPolicy.isValidMinimumSecondsBetweenDeliveries(0)).isTrue()
        assertThat(NativeAdPlacementPolicy.isValidMinimumSecondsBetweenDeliveries(59)).isFalse()
        assertThat(NativeAdPlacementPolicy.isValidMinimumSecondsBetweenDeliveries(60)).isTrue()
        assertThat(NativeAdPlacementPolicy.isValidMinimumSecondsBetweenDeliveries(2_592_001)).isFalse()
    }

    @Test
    fun `disabled scheduled expired and undersized feeds do not receive a slot`() {
        assertThat(NativeAdPlacementPolicy.position(policy(enabled = false), 10, now, 0)).isNull()
        assertThat(NativeAdPlacementPolicy.position(policy(startsAt = now.plusSeconds(1)), 10, now, 0)).isNull()
        assertThat(NativeAdPlacementPolicy.position(policy(endsAt = now), 10, now, 0)).isNull()
        assertThat(NativeAdPlacementPolicy.position(policy(), 3, now, 0)).isNull()
    }

    @Test
    fun `position always leaves two questions before and one after the slot`() {
        val generated = (0L..100L).mapNotNull { entropy ->
            NativeAdPlacementPolicy.position(
                policy(earliestPosition = 0, latestPosition = 99),
                feedItemCount = 10,
                now = now,
                entropy = entropy,
            )
        }

        assertThat(generated).allMatch { it in 2..9 }
        assertThat(generated).doesNotContain(10)
    }

    private fun policy(
        enabled: Boolean = true,
        earliestPosition: Int = 2,
        latestPosition: Int = 7,
        startsAt: Instant? = null,
        endsAt: Instant? = null,
    ) = NativeAdPlacementPolicyEntity(
        enabled = enabled,
        earliestPosition = earliestPosition,
        latestPosition = latestPosition,
        startsAt = startsAt,
        endsAt = endsAt,
    )
}
