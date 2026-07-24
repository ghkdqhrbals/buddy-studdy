package com.buddystudy.backend.monitoring.adapter.inbound.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.r2dbc.spi.ConnectionFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory

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
            runtimeKind = "native-image",
            runtimeName = "GraalVM Native Image",
            runtimeVersion = "25",
            runtimeMetricsDegraded = true,
            runtimeMetricsUnavailable = "heapMemoryUsage",
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
            reactorNettyEventLoopPendingTasks = 3.0,
            reactorNettyEventLoopMaxPendingTasks = 2.0,
            reactorNettyActiveConnections = 4.0,
            reactorNettyDirectMemoryBytes = 1_024.0,
        )

        val payload = ObjectMapper().readTree(formatRuntimeMetrics(ObjectMapper(), snapshot))

        assertThat(payload.size()).isEqualTo(52)
        assertThat(payload["processCpuPercent"].doubleValue()).isEqualTo(14.5)
        assertThat(payload["heapUsedBytes"].longValue()).isEqualTo(100)
        assertThat(payload["threadsBlocked"].intValue()).isEqualTo(1)
        assertThat(payload["dbPoolPending"].intValue()).isZero()
        assertThat(payload["rootDiskUsedBytes"].longValue()).isEqualTo(1_024)
        assertThat(payload["networkTransmitBytesTotal"].longValue()).isEqualTo(20_000)
        assertThat(payload["reactorNettyEventLoopPendingTasks"].doubleValue()).isEqualTo(3.0)
        assertThat(payload["reactorNettyActiveConnections"].doubleValue()).isEqualTo(4.0)
        assertThat(payload["runtimeKind"].textValue()).isEqualTo("native-image")
        assertThat(payload["runtimeVersion"].textValue()).isEqualTo("25")
        assertThat(payload["runtimeMetricsDegraded"].booleanValue()).isTrue()
        assertThat(payload["runtimeMetricsUnavailable"].textValue()).isEqualTo("heapMemoryUsage")
    }

    @Test
    fun `aggregates gauges across reactor netty event loops`() {
        val registry = SimpleMeterRegistry()
        Gauge.builder("reactor.netty.eventloop.pending.tasks") { 2.0 }
            .tag("name", "event-loop-1")
            .register(registry)
        Gauge.builder("reactor.netty.eventloop.pending.tasks") { 5.0 }
            .tag("name", "event-loop-2")
            .register(registry)

        val aggregate = aggregateGauges(registry, "reactor.netty.eventloop.pending.tasks")

        assertThat(aggregate.total).isEqualTo(7.0)
        assertThat(aggregate.maximum).isEqualTo(5.0)
    }

    @Test
    fun `prefers standard Micrometer JVM Netty and R2DBC pool gauges`() {
        val registry = SimpleMeterRegistry()
        Gauge.builder("jvm.memory.used") { 4_096.0 }
            .tags("area", "heap", "id", "test")
            .register(registry)
        Gauge.builder("jvm.threads.live") { 27.0 }.register(registry)
        Gauge.builder("jvm.classes.loaded") { 1_200.0 }.register(registry)
        Counter.builder("jvm.classes.loaded.count").register(registry).increment(1_500.0)
        Counter.builder("jvm.classes.unloaded").register(registry).increment(300.0)
        Gauge.builder("r2dbc.pool.acquired") { 3.0 }.tag("name", "connectionFactory").register(registry)
        Gauge.builder("r2dbc.pool.allocated") { 10.0 }.tag("name", "connectionFactory").register(registry)
        Gauge.builder("r2dbc.pool.idle") { 7.0 }.tag("name", "connectionFactory").register(registry)
        Gauge.builder("r2dbc.pool.pending") { 2.0 }.tag("name", "connectionFactory").register(registry)
        Gauge.builder("r2dbc.pool.max.allocated") { 20.0 }.tag("name", "connectionFactory").register(registry)
        Gauge.builder("reactor.netty.eventloop.pending.tasks") { 5.0 }
            .tag("name", "reactor-http-nio-1")
            .register(registry)
        val provider = StaticListableBeanFactory().getBeanProvider(ConnectionFactory::class.java)

        val snapshot = RuntimeMetricsSampler(registry, provider).snapshot()

        assertThat(snapshot.heapUsedBytes).isEqualTo(4_096)
        assertThat(snapshot.threadsLive).isEqualTo(27)
        assertThat(snapshot.classesLoaded).isEqualTo(1_200)
        assertThat(snapshot.classesLoadedTotal).isEqualTo(1_500)
        assertThat(snapshot.classesUnloadedTotal).isEqualTo(300)
        assertThat(snapshot.dbPoolAcquired).isEqualTo(3)
        assertThat(snapshot.dbPoolAllocated).isEqualTo(10)
        assertThat(snapshot.dbPoolIdle).isEqualTo(7)
        assertThat(snapshot.dbPoolPending).isEqualTo(2)
        assertThat(snapshot.dbPoolMaxAllocated).isEqualTo(20)
        assertThat(snapshot.reactorNettyEventLoopPendingTasks).isEqualTo(5.0)
    }
}
