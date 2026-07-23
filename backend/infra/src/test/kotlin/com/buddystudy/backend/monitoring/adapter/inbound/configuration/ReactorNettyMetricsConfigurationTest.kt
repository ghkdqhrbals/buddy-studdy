package com.buddystudy.backend.monitoring.adapter.inbound.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReactorNettyMetricsConfigurationTest {
    @Test
    fun `normalizes dynamic path segments to bounded metric tags`() {
        assertThat(normalizeMetricUri("/api/v1/studies/42/questions"))
            .isEqualTo("/api/v1/studies/{id}/questions")
        assertThat(normalizeMetricUri("/api/v1/records/25316cca-c9f1-46fc-a355-630553772173?detail=true"))
            .isEqualTo("/api/v1/records/{id}")
        assertThat(normalizeMetricUri("/api/v1/questions/opaque_record_identifier_12345"))
            .isEqualTo("/api/v1/questions/{id}")
    }

    @Test
    fun `keeps stable api routes readable`() {
        assertThat(normalizeMetricUri("/api/v1/health/readiness"))
            .isEqualTo("/api/v1/health/readiness")
        assertThat(normalizeMetricUri("not-a-path"))
            .isEqualTo("UNKNOWN")
    }
}
