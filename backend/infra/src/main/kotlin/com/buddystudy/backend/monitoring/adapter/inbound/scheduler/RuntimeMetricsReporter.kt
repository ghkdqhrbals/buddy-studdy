package com.buddystudy.backend.monitoring.adapter.inbound.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.PoolMetrics
import io.r2dbc.spi.ConnectionFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

@Component
@ConditionalOnProperty(
    prefix = "buddystudy.monitoring.runtime-metrics",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class RuntimeMetricsReporter(
    meterRegistry: MeterRegistry,
    connectionFactoryProvider: ObjectProvider<ConnectionFactory>,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val sampler = RuntimeMetricsSampler(meterRegistry, connectionFactoryProvider)

    @Scheduled(
        fixedDelayString = "\${buddystudy.monitoring.runtime-metrics.interval-ms:30000}",
        initialDelayString = "\${buddystudy.monitoring.runtime-metrics.initial-delay-ms:30000}",
    )
    fun report() {
        runCatching {
            logger.info("runtime_metrics {}", formatRuntimeMetrics(objectMapper, sampler.snapshot()))
        }.onFailure { error ->
            logger.warn("runtime_metrics_collection_failed message={}", error.message)
        }
    }
}

internal class RuntimeMetricsSampler(
    private val meterRegistry: MeterRegistry,
    private val connectionFactoryProvider: ObjectProvider<ConnectionFactory>,
) {
    private val memory = ManagementFactory.getMemoryMXBean()
    private val threads = ManagementFactory.getThreadMXBean()
    private val runtime = ManagementFactory.getRuntimeMXBean()
    private val operatingSystem = ManagementFactory.getOperatingSystemMXBean()
    private val classes = ManagementFactory.getClassLoadingMXBean()
    private val garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans()
    private val directBuffer = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
        .firstOrNull { it.name.equals("direct", ignoreCase = true) }

    fun snapshot(): RuntimeMetricsSnapshot {
        val heap = memory.heapMemoryUsage
        val nonHeap = memory.nonHeapMemoryUsage
        val threadStates = threadStateCounts()
        val hostMemory = readHostMemory()
        val rootDisk = readRootDisk()
        val network = readNetworkIo()
        val pool = connectionPoolMetrics()
        val eventLoopPendingTasks = gaugeAggregate("reactor.netty.eventloop.pending.tasks")
        val activeConnections = gaugeAggregate("reactor.netty.http.server.connections.active")
        val nettyDirectMemory = gaugeAggregate("reactor.netty.bytebuf.allocator.active.direct.memory")

        return RuntimeMetricsSnapshot(
            capturedAtEpochMs = System.currentTimeMillis(),
            availableProcessors = operatingSystem.availableProcessors,
            processCpuPercent = percentageGauge("process.cpu.usage"),
            systemCpuPercent = percentageGauge("system.cpu.usage"),
            systemLoadAverage1m = operatingSystem.systemLoadAverage.finiteOrNull(),
            processUptimeSeconds = runtime.uptime / 1_000,
            hostMemoryTotalBytes = hostMemory?.totalBytes,
            hostMemoryAvailableBytes = hostMemory?.availableBytes,
            hostMemoryUsedBytes = hostMemory?.usedBytes,
            processResidentMemoryBytes = readProcessResidentMemoryBytes(),
            processOpenFileDescriptors = countOpenFileDescriptors(),
            rootDiskTotalBytes = rootDisk?.totalBytes,
            rootDiskUsableBytes = rootDisk?.usableBytes,
            rootDiskUsedBytes = rootDisk?.usedBytes,
            networkReceiveBytesTotal = network?.receiveBytes,
            networkTransmitBytesTotal = network?.transmitBytes,
            heapUsedBytes = heap.used.nonNegativeOrNull(),
            heapCommittedBytes = heap.committed.nonNegativeOrNull(),
            heapMaxBytes = heap.max.nonNegativeOrNull(),
            nonHeapUsedBytes = nonHeap.used.nonNegativeOrNull(),
            nonHeapCommittedBytes = nonHeap.committed.nonNegativeOrNull(),
            directBufferCount = directBuffer?.count?.nonNegativeOrNull(),
            directBufferMemoryUsedBytes = directBuffer?.memoryUsed?.nonNegativeOrNull(),
            directBufferCapacityBytes = directBuffer?.totalCapacity?.nonNegativeOrNull(),
            threadsLive = threads.threadCount,
            threadsDaemon = threads.daemonThreadCount,
            threadsPeak = threads.peakThreadCount,
            threadsStartedTotal = threads.totalStartedThreadCount,
            threadsRunnable = threadStates[Thread.State.RUNNABLE] ?: 0,
            threadsBlocked = threadStates[Thread.State.BLOCKED] ?: 0,
            threadsWaiting = threadStates[Thread.State.WAITING] ?: 0,
            threadsTimedWaiting = threadStates[Thread.State.TIMED_WAITING] ?: 0,
            gcCollectionsTotal = garbageCollectors.sumOf { it.collectionCount.coerceAtLeast(0) },
            gcCollectionTimeMsTotal = garbageCollectors.sumOf { it.collectionTime.coerceAtLeast(0) },
            classesLoaded = classes.loadedClassCount,
            classesLoadedTotal = classes.totalLoadedClassCount,
            classesUnloadedTotal = classes.unloadedClassCount,
            dbPoolAcquired = pool?.acquiredSize(),
            dbPoolAllocated = pool?.allocatedSize(),
            dbPoolIdle = pool?.idleSize(),
            dbPoolPending = pool?.pendingAcquireSize(),
            dbPoolMaxAllocated = pool?.maxAllocatedSize,
            dbPoolMaxPending = pool?.maxPendingAcquireSize,
            reactorNettyEventLoopPendingTasks = eventLoopPendingTasks.total,
            reactorNettyEventLoopMaxPendingTasks = eventLoopPendingTasks.maximum,
            reactorNettyActiveConnections = activeConnections.total,
            reactorNettyDirectMemoryBytes = nettyDirectMemory.total,
            jvmName = runtime.vmName,
            jvmVersion = runtime.vmVersion,
        )
    }

    private fun percentageGauge(name: String): Double? =
        meterRegistry.find(name).gauge()?.value()
            ?.takeIf(Double::isFinite)
            ?.times(100.0)

    private fun gaugeAggregate(name: String): GaugeAggregate =
        aggregateGauges(meterRegistry, name)

    private fun threadStateCounts(): Map<Thread.State, Int> =
        runCatching {
            threads.getThreadInfo(threads.allThreadIds)
                .filterNotNull()
                .groupingBy { it.threadState }
                .eachCount()
        }.getOrDefault(emptyMap())

    private fun connectionPoolMetrics(): PoolMetrics? =
        (connectionFactoryProvider.ifAvailable as? ConnectionPool)?.metrics?.orElse(null)
}

internal data class RuntimeMetricsSnapshot(
    val capturedAtEpochMs: Long,
    val availableProcessors: Int,
    val processCpuPercent: Double?,
    val systemCpuPercent: Double?,
    val systemLoadAverage1m: Double?,
    val processUptimeSeconds: Long,
    val hostMemoryTotalBytes: Long?,
    val hostMemoryAvailableBytes: Long?,
    val hostMemoryUsedBytes: Long?,
    val processResidentMemoryBytes: Long?,
    val processOpenFileDescriptors: Long?,
    val rootDiskTotalBytes: Long?,
    val rootDiskUsableBytes: Long?,
    val rootDiskUsedBytes: Long?,
    val networkReceiveBytesTotal: Long?,
    val networkTransmitBytesTotal: Long?,
    val heapUsedBytes: Long?,
    val heapCommittedBytes: Long?,
    val heapMaxBytes: Long?,
    val nonHeapUsedBytes: Long?,
    val nonHeapCommittedBytes: Long?,
    val directBufferCount: Long?,
    val directBufferMemoryUsedBytes: Long?,
    val directBufferCapacityBytes: Long?,
    val threadsLive: Int,
    val threadsDaemon: Int,
    val threadsPeak: Int,
    val threadsStartedTotal: Long,
    val threadsRunnable: Int,
    val threadsBlocked: Int,
    val threadsWaiting: Int,
    val threadsTimedWaiting: Int,
    val gcCollectionsTotal: Long,
    val gcCollectionTimeMsTotal: Long,
    val classesLoaded: Int,
    val classesLoadedTotal: Long,
    val classesUnloadedTotal: Long,
    val dbPoolAcquired: Int?,
    val dbPoolAllocated: Int?,
    val dbPoolIdle: Int?,
    val dbPoolPending: Int?,
    val dbPoolMaxAllocated: Int?,
    val dbPoolMaxPending: Int?,
    val reactorNettyEventLoopPendingTasks: Double?,
    val reactorNettyEventLoopMaxPendingTasks: Double?,
    val reactorNettyActiveConnections: Double?,
    val reactorNettyDirectMemoryBytes: Double?,
    val jvmName: String,
    val jvmVersion: String,
)

internal data class GaugeAggregate(
    val total: Double?,
    val maximum: Double?,
)

internal fun aggregateGauges(
    meterRegistry: MeterRegistry,
    name: String,
): GaugeAggregate {
    val values = meterRegistry.find(name)
        .gauges()
        .map { it.value() }
        .filter(Double::isFinite)
    return GaugeAggregate(
        total = values.takeIf(List<Double>::isNotEmpty)?.sum(),
        maximum = values.maxOrNull(),
    )
}

internal data class HostMemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
) {
    val usedBytes: Long = (totalBytes - availableBytes).coerceAtLeast(0)
}

internal data class DiskSnapshot(
    val totalBytes: Long,
    val usableBytes: Long,
) {
    val usedBytes: Long = (totalBytes - usableBytes).coerceAtLeast(0)
}

internal data class NetworkIoSnapshot(
    val receiveBytes: Long,
    val transmitBytes: Long,
)

internal fun formatRuntimeMetrics(
    objectMapper: ObjectMapper,
    snapshot: RuntimeMetricsSnapshot,
): String = objectMapper.writeValueAsString(snapshot)

internal fun parseProcMemInfo(content: String): HostMemorySnapshot? {
    val values = parseProcKilobytes(content)
    val total = values["MemTotal"] ?: return null
    val available = values["MemAvailable"] ?: values["MemFree"] ?: return null
    return HostMemorySnapshot(totalBytes = total, availableBytes = available)
}

internal fun parseProcStatusRss(content: String): Long? =
    parseProcKilobytes(content)["VmRSS"]

internal fun parseProcNetworkDevice(content: String): NetworkIoSnapshot? {
    var receive = 0L
    var transmit = 0L
    var found = false
    content.lineSequence().forEach { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@forEach
        val interfaceName = line.substring(0, separator).trim()
        if (interfaceName == "lo") return@forEach
        val fields = line.substring(separator + 1).trim().split(Regex("\\s+"))
        val receivedBytes = fields.getOrNull(0)?.toLongOrNull() ?: return@forEach
        val transmittedBytes = fields.getOrNull(8)?.toLongOrNull() ?: return@forEach
        receive += receivedBytes
        transmit += transmittedBytes
        found = true
    }
    return if (found) NetworkIoSnapshot(receive, transmit) else null
}

private fun readHostMemory(): HostMemorySnapshot? =
    readText(Path.of("/proc/meminfo"))?.let(::parseProcMemInfo)

private fun readProcessResidentMemoryBytes(): Long? =
    readText(Path.of("/proc/self/status"))?.let(::parseProcStatusRss)

private fun readNetworkIo(): NetworkIoSnapshot? =
    readText(Path.of("/proc/net/dev"))?.let(::parseProcNetworkDevice)

private fun readRootDisk(): DiskSnapshot? =
    runCatching {
        val root = Files.getFileStore(Path.of("/"))
        DiskSnapshot(totalBytes = root.totalSpace, usableBytes = root.usableSpace)
    }.getOrNull()

private fun countOpenFileDescriptors(): Long? {
    val directory = Path.of("/proc/self/fd")
    if (!directory.exists()) return null
    return runCatching { Files.list(directory).use { it.count() } }.getOrNull()
}

private fun readText(path: Path): String? {
    if (!path.exists()) return null
    return runCatching { Files.readString(path) }.getOrNull()
}

private fun parseProcKilobytes(content: String): Map<String, Long> =
    content.lineSequence().mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        val key = line.substring(0, separator)
        val kilobytes = line.substring(separator + 1)
            .trim()
            .substringBefore(' ')
            .toLongOrNull()
            ?: return@mapNotNull null
        key to kilobytes * 1_024
    }.toMap()

private fun Double.finiteOrNull(): Double? = takeIf(Double::isFinite)?.takeIf { it >= 0.0 }

private fun Long.nonNegativeOrNull(): Long? = takeIf { it >= 0 }
