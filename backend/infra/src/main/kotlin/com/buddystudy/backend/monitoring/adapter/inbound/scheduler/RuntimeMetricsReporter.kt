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
            logger.warn(
                "runtime_metrics_collection_failed errorType={} message={}",
                error.javaClass.simpleName,
                error.message,
                error,
            )
        }
    }
}

internal class RuntimeMetricsSampler(
    private val meterRegistry: MeterRegistry,
    private val connectionFactoryProvider: ObjectProvider<ConnectionFactory>,
) {
    fun snapshot(): RuntimeMetricsSnapshot {
        val unavailable = linkedSetOf<String>()
        val memory = collect("memoryMxBean", unavailable) { ManagementFactory.getMemoryMXBean() }
        val threads = collect("threadMxBean", unavailable) { ManagementFactory.getThreadMXBean() }
        val runtime = collect("runtimeMxBean", unavailable) { ManagementFactory.getRuntimeMXBean() }
        val operatingSystem = collect("operatingSystemMxBean", unavailable) {
            ManagementFactory.getOperatingSystemMXBean()
        }
        val classes = collect("classLoadingMxBean", unavailable) { ManagementFactory.getClassLoadingMXBean() }
        val garbageCollectors = collect("garbageCollectorMxBeans", unavailable) {
            ManagementFactory.getGarbageCollectorMXBeans()
        }
        val directBuffer = collect("directBufferMxBean", unavailable) {
            ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
                .firstOrNull { it.name.equals("direct", ignoreCase = true) }
        }
        val heap = collect("heapMemoryUsage", unavailable) { memory?.heapMemoryUsage }
        val nonHeap = collect("nonHeapMemoryUsage", unavailable) { memory?.nonHeapMemoryUsage }
        val threadStates = collect("threadStates", unavailable) {
            threads?.let(::threadStateCounts).orEmpty()
        }.orEmpty()
        val hostMemory = collect("hostMemory", unavailable) { readHostMemory() }
        val rootDisk = collect("rootDisk", unavailable) { readRootDisk() }
        val network = collect("networkIo", unavailable) { readNetworkIo() }
        val residentMemory = collect("processResidentMemory", unavailable) {
            readProcessResidentMemoryBytes()
        }
        val openFileDescriptors = collect("openFileDescriptors", unavailable) {
            countOpenFileDescriptors()
        }
        val pool = collect("r2dbcPool", unavailable) { connectionPoolMetrics() }
        val processCpu = collect("processCpu", unavailable) { percentageGauge("process.cpu.usage") }
        val systemCpu = collect("systemCpu", unavailable) { percentageGauge("system.cpu.usage") }
        val eventLoopPendingTasks = collect("nettyEventLoopPendingTasks", unavailable) {
            gaugeAggregate("reactor.netty.eventloop.pending.tasks")
        } ?: GaugeAggregate(null, null)
        val activeConnections = collect("nettyActiveConnections", unavailable) {
            gaugeAggregate("reactor.netty.http.server.connections.active")
        } ?: GaugeAggregate(null, null)
        val nettyDirectMemory = collect("nettyDirectMemory", unavailable) {
            gaugeAggregate("reactor.netty.bytebuf.allocator.active.direct.memory")
        } ?: GaugeAggregate(null, null)
        val runtimeKind = runtimeKind()
        val runtimeName = collect("runtimeName", unavailable) { runtime?.vmName }
            ?: System.getProperty("java.vm.name")
            ?: runtimeKind
        val runtimeVersion = collect("runtimeVersion", unavailable) { runtime?.vmVersion }
            ?: System.getProperty("java.vm.version")
            ?: System.getProperty("java.version")
            ?: "unknown"
        if (processCpu == null) unavailable += "processCpu"
        if (systemCpu == null) unavailable += "systemCpu"
        if (hostMemory == null) unavailable += "hostMemory"
        if (residentMemory == null) unavailable += "processResidentMemory"
        if (rootDisk == null) unavailable += "rootDisk"
        if (network == null) unavailable += "networkIo"
        if (pool == null) unavailable += "r2dbcPool"
        if (eventLoopPendingTasks.total == null) unavailable += "nettyEventLoopPendingTasks"
        if (activeConnections.total == null) unavailable += "nettyActiveConnections"

        return RuntimeMetricsSnapshot(
            capturedAtEpochMs = System.currentTimeMillis(),
            runtimeKind = runtimeKind,
            runtimeName = runtimeName,
            runtimeVersion = runtimeVersion,
            availableProcessors = collect("availableProcessors", unavailable) {
                operatingSystem?.availableProcessors
            } ?: Runtime.getRuntime().availableProcessors(),
            processCpuPercent = processCpu,
            systemCpuPercent = systemCpu,
            systemLoadAverage1m = collect("systemLoadAverage", unavailable) {
                operatingSystem?.systemLoadAverage?.finiteOrNull()
            },
            processUptimeSeconds = collect("processUptime", unavailable) { runtime?.uptime?.div(1_000) },
            hostMemoryTotalBytes = hostMemory?.totalBytes,
            hostMemoryAvailableBytes = hostMemory?.availableBytes,
            hostMemoryUsedBytes = hostMemory?.usedBytes,
            processResidentMemoryBytes = residentMemory,
            processOpenFileDescriptors = openFileDescriptors,
            rootDiskTotalBytes = rootDisk?.totalBytes,
            rootDiskUsableBytes = rootDisk?.usableBytes,
            rootDiskUsedBytes = rootDisk?.usedBytes,
            networkReceiveBytesTotal = network?.receiveBytes,
            networkTransmitBytesTotal = network?.transmitBytes,
            heapUsedBytes = heap?.used?.nonNegativeOrNull(),
            heapCommittedBytes = heap?.committed?.nonNegativeOrNull(),
            heapMaxBytes = heap?.max?.nonNegativeOrNull(),
            nonHeapUsedBytes = nonHeap?.used?.nonNegativeOrNull(),
            nonHeapCommittedBytes = nonHeap?.committed?.nonNegativeOrNull(),
            directBufferCount = collect("directBufferCount", unavailable) {
                directBuffer?.count?.nonNegativeOrNull()
            },
            directBufferMemoryUsedBytes = collect("directBufferMemory", unavailable) {
                directBuffer?.memoryUsed?.nonNegativeOrNull()
            },
            directBufferCapacityBytes = collect("directBufferCapacity", unavailable) {
                directBuffer?.totalCapacity?.nonNegativeOrNull()
            },
            threadsLive = collect("threadsLive", unavailable) { threads?.threadCount },
            threadsDaemon = collect("threadsDaemon", unavailable) { threads?.daemonThreadCount },
            threadsPeak = collect("threadsPeak", unavailable) { threads?.peakThreadCount },
            threadsStartedTotal = collect("threadsStarted", unavailable) { threads?.totalStartedThreadCount },
            threadsRunnable = threadStates[Thread.State.RUNNABLE],
            threadsBlocked = threadStates[Thread.State.BLOCKED],
            threadsWaiting = threadStates[Thread.State.WAITING],
            threadsTimedWaiting = threadStates[Thread.State.TIMED_WAITING],
            gcCollectionsTotal = collect("gcCollections", unavailable) {
                garbageCollectors?.sumOf { it.collectionCount.coerceAtLeast(0) }
            },
            gcCollectionTimeMsTotal = collect("gcCollectionTime", unavailable) {
                garbageCollectors?.sumOf { it.collectionTime.coerceAtLeast(0) }
            },
            classesLoaded = collect("classesLoaded", unavailable) { classes?.loadedClassCount },
            classesLoadedTotal = collect("classesLoadedTotal", unavailable) { classes?.totalLoadedClassCount },
            classesUnloadedTotal = collect("classesUnloadedTotal", unavailable) { classes?.unloadedClassCount },
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
            runtimeMetricsDegraded = unavailable.isNotEmpty(),
            runtimeMetricsUnavailable = unavailable.takeIf(Set<String>::isNotEmpty)?.joinToString(","),
        )
    }

    private fun percentageGauge(name: String): Double? =
        meterRegistry.find(name).gauge()?.value()
            ?.takeIf(Double::isFinite)
            ?.times(100.0)

    private fun gaugeAggregate(name: String): GaugeAggregate =
        aggregateGauges(meterRegistry, name)

    private fun threadStateCounts(threads: java.lang.management.ThreadMXBean): Map<Thread.State, Int> =
        threads.getThreadInfo(threads.allThreadIds)
            .filterNotNull()
            .groupingBy { it.threadState }
            .eachCount()

    private fun connectionPoolMetrics(): PoolMetrics? =
        (connectionFactoryProvider.ifAvailable as? ConnectionPool)?.metrics?.orElse(null)

    private fun runtimeKind(): String =
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            "native-image"
        } else {
            "jvm"
        }

    private inline fun <T> collect(
        name: String,
        unavailable: MutableSet<String>,
        block: () -> T?,
    ): T? =
        try {
            block()
        } catch (_: Throwable) {
            unavailable += name
            null
        }
}

internal data class RuntimeMetricsSnapshot(
    val capturedAtEpochMs: Long,
    val runtimeKind: String,
    val runtimeName: String,
    val runtimeVersion: String,
    val runtimeMetricsDegraded: Boolean,
    val runtimeMetricsUnavailable: String?,
    val availableProcessors: Int,
    val processCpuPercent: Double?,
    val systemCpuPercent: Double?,
    val systemLoadAverage1m: Double?,
    val processUptimeSeconds: Long?,
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
    val threadsLive: Int?,
    val threadsDaemon: Int?,
    val threadsPeak: Int?,
    val threadsStartedTotal: Long?,
    val threadsRunnable: Int?,
    val threadsBlocked: Int?,
    val threadsWaiting: Int?,
    val threadsTimedWaiting: Int?,
    val gcCollectionsTotal: Long?,
    val gcCollectionTimeMsTotal: Long?,
    val classesLoaded: Int?,
    val classesLoadedTotal: Long?,
    val classesUnloadedTotal: Long?,
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
