package com.buddystudy.backend.monitoring.adapter.inbound.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RuntimeMetricsReporterTest {
    @Test
    fun `parses Linux host and process memory values as bytes`() {
        val memory = parseProcMemInfo(
            """
            MemTotal:       16384000 kB
            MemFree:         1024000 kB
            MemAvailable:    4096000 kB
            """.trimIndent(),
        )
        val resident = parseProcStatusRss(
            """
            Name:	buddystudy
            VmRSS:	  131072 kB
            """.trimIndent(),
        )

        assertThat(memory?.totalBytes).isEqualTo(16_384_000L * 1_024)
        assertThat(memory?.availableBytes).isEqualTo(4_096_000L * 1_024)
        assertThat(memory?.usedBytes).isEqualTo(12_288_000L * 1_024)
        assertThat(resident).isEqualTo(131_072L * 1_024)
    }

    @Test
    fun `parses non-loopback network counters`() {
        val network = parseProcNetworkDevice(
            """
            Inter-|   Receive                                                |  Transmit
             face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed
               lo: 100 1 0 0 0 0 0 0 100 1 0 0 0 0 0 0
             eth0: 1200 2 0 0 0 0 0 0 3400 3 0 0 0 0 0 0
             eth1: 300 1 0 0 0 0 0 0 500 1 0 0 0 0 0 0
            """.trimIndent(),
        )

        assertThat(network?.receiveBytes).isEqualTo(1_500)
        assertThat(network?.transmitBytes).isEqualTo(3_900)
    }

    @Test
    fun `formats a flat Loki-friendly runtime metric payload`() {
        val snapshot = RuntimeMetricsSnapshot(
            capturedAtEpochMs = 1_700_000_000_000,
            availableProcessors = 2,
            processCpuPercent = 14.5,
            systemCpuPercent = 38.2,
            systemLoadAverage1m = 0.75,
            processUptimeSeconds = 120,
            hostMemoryTotalBytes = 1_024,
            hostMemoryAvailableBytes = 512,
            hostMemoryUsedBytes = 512,
            processResidentMemoryBytes = 256,
            processOpenFileDescriptors = 42,
            rootDiskTotalBytes = 4_096,
            rootDiskUsableBytes = 3_072,
            rootDiskUsedBytes = 1_024,
            networkReceiveBytesTotal = 10_000,
            networkTransmitBytesTotal = 20_000,
            heapUsedBytes = 100,
            heapCommittedBytes = 200,
            heapMaxBytes = 300,
            nonHeapUsedBytes = 40,
            nonHeapCommittedBytes = 50,
            directBufferCount = 2,
            directBufferMemoryUsedBytes = 20,
            directBufferCapacityBytes = 30,
            threadsLive = 12,
            threadsDaemon = 10,
            threadsPeak = 14,
            threadsStartedTotal = 20,
            threadsRunnable = 3,
            threadsBlocked = 1,
            threadsWaiting = 4,
            threadsTimedWaiting = 4,
            gcCollectionsTotal = 5,
            gcCollectionTimeMsTotal = 25,
            classesLoaded = 500,
            classesLoadedTotal = 600,
            classesUnloadedTotal = 100,
            dbPoolAcquired = 2,
            dbPoolAllocated = 5,
            dbPoolIdle = 3,
            dbPoolPending = 0,
            dbPoolMaxAllocated = 10,
            dbPoolMaxPending = 20,
            jvmName = "GraalVM",
            jvmVersion = "25",
        )

        val payload = ObjectMapper().readTree(formatRuntimeMetrics(ObjectMapper(), snapshot))

        assertThat(payload["processCpuPercent"].doubleValue()).isEqualTo(14.5)
        assertThat(payload["heapUsedBytes"].longValue()).isEqualTo(100)
        assertThat(payload["threadsBlocked"].intValue()).isEqualTo(1)
        assertThat(payload["dbPoolPending"].intValue()).isZero()
        assertThat(payload["rootDiskUsedBytes"].longValue()).isEqualTo(1_024)
        assertThat(payload["networkTransmitBytesTotal"].longValue()).isEqualTo(20_000)
        assertThat(payload["jvmVersion"].textValue()).isEqualTo("25")
    }
}
