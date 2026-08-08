package com.buddystudy.backend.common.adapter.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamMessage
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamConsumerOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.SmartLifecycle
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.kotlinFunction

@Component
class RedisStreamAnnotationManager(
    private val streams: RedisStreamConsumerOperations,
    private val dispatcher: RedisStreamMessageDispatcher,
    private val environment: Environment,
    private val beanFactory: ListableBeanFactory,
) : SmartInitializingSingleton, SmartLifecycle, DisposableBean {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val listeners = CopyOnWriteArrayList<ListenerHandler>()
    private val schedulers = CopyOnWriteArrayList<SchedulerHandler>()
    private val running = AtomicBoolean(false)
    @Volatile
    private var workerScope: CoroutineScope? = null

    override fun afterSingletonsInstantiated() {
        scanHandlers()
    }

    @Synchronized
    override fun start() {
        if (!running.compareAndSet(false, true)) return
        val scope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.IO + CoroutineName("redis-stream-annotations"),
            )
        workerScope = scope
        val enabledListeners = listeners.filter { isEnabled(it.annotation.enabledProperty) }
        val enabledSchedulers = schedulers.filter { isEnabled(it.annotation.enabledProperty) }
        enabledListeners.forEach { handler ->
            startListener(handler, handler.annotation.topic, scope)
        }
        enabledSchedulers.forEach { handler ->
            startScheduler(handler, handler.annotation.topic, scope)
        }
        logger.info(
            "redis_stream_annotations_started registeredListeners={} registeredSchedulers={} startedListeners={} startedSchedulers={}",
            listeners.size,
            schedulers.size,
            enabledListeners.size,
            enabledSchedulers.size,
        )
    }

    @Synchronized
    override fun stop() {
        running.set(false)
        workerScope?.cancel()
        workerScope = null
    }

    override fun stop(callback: Runnable) {
        stop()
        callback.run()
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE

    override fun destroy() = stop()

    private fun scanHandlers() {
        beanFactory.getBeanNamesForType(Any::class.java, true, false).forEach { beanName ->
            val bean = runCatching { beanFactory.getBean(beanName) }.getOrNull() ?: return@forEach
            if (bean === this) return@forEach
            val targetClass = AopUtils.getTargetClass(bean)
            targetClass.declaredMethods.forEach { method ->
                method.getAnnotation(StreamListener::class.java)?.let { annotation ->
                    listeners += ListenerHandler(
                        beanName,
                        bean,
                        handlerMethod(beanName, method, annotation.payloadType.java),
                        annotation,
                    )
                }
                method.getAnnotation(StreamScheduler::class.java)?.let { annotation ->
                    schedulers += SchedulerHandler(
                        beanName,
                        bean,
                        handlerMethod(beanName, method, annotation.payloadType.java),
                        annotation,
                    )
                }
            }
        }
    }

    private fun startListener(handler: ListenerHandler, topic: RedisStreamTopic, scope: CoroutineScope) {
        val annotation = handler.annotation
        val concurrency = annotation.concurrencyProperty
            .takeIf(String::isNotBlank)
            ?.let { environment.getProperty(it, Int::class.java, annotation.concurrency) }
            ?: annotation.concurrency
        repeat(concurrency.coerceIn(1, MAX_CONCURRENCY)) { workerIndex ->
            val consumer = consumerName(annotation.consumer, workerIndex)
            scope.launch(CoroutineName("stream-listener-${handler.beanName}-${topic.apiName}-$consumer")) {
                while (currentCoroutineContext().isActive) {
                    try {
                        val blockTimeMs = annotation.blockTimeMs.coerceAtLeast(1)
                        val messages = withTimeoutOrNull(readDeadlineMs(blockTimeMs)) {
                            streams.readNew(
                                topic = topic,
                                group = annotation.group,
                                consumer = consumer,
                                count = annotation.batchSize.coerceAtLeast(1),
                                timeout = Duration.ofMillis(blockTimeMs),
                            )
                        }
                        if (messages == null) {
                            logger.warn(
                                "redis_stream_listener_read_timed_out bean={} method={} topic={} group={} consumer={} blockTimeMs={}",
                                handler.beanName,
                                handler.method.name,
                                topic.apiName,
                                annotation.group,
                                consumer,
                                blockTimeMs,
                            )
                            delay(annotation.pollDelayMs.coerceAtLeast(1))
                            continue
                        }
                        if (messages.isNotEmpty()) {
                            logger.debug(
                                "redis_stream_listener_batch_received bean={} method={} topic={} group={} consumer={} count={}",
                                handler.beanName,
                                handler.method.name,
                                topic.apiName,
                                annotation.group,
                                consumer,
                                messages.size,
                            )
                        }
                        messages.forEach { dispatch(handler.bean, handler.method, annotation, it, claimed = false) }
                        if (messages.isEmpty()) delay(annotation.pollDelayMs.coerceAtLeast(1))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (error.isFatalStreamWorkerFailure()) throw error
                        if (!running.get() || !currentCoroutineContext().isActive) return@launch
                        logger.warn(
                            "redis_stream_listener_poll_failed bean={} method={} topic={} group={} consumer={} errorType={} error={}",
                            handler.beanName,
                            handler.method.name,
                            topic.apiName,
                            annotation.group,
                            consumer,
                            error.javaClass.name,
                            error.message,
                            error,
                        )
                        delay(annotation.pollDelayMs.coerceAtLeast(1))
                    }
                }
            }
        }
    }

    private fun startScheduler(handler: SchedulerHandler, topic: RedisStreamTopic, scope: CoroutineScope) {
        val annotation = handler.annotation
        scope.launch(CoroutineName("stream-scheduler-${handler.beanName}-${topic.apiName}-${annotation.consumer}")) {
            ensureRecoveryConsumer(handler, topic)
            delay(annotation.initialDelayMs.coerceAtLeast(0))
            var startId = START_ID
            while (currentCoroutineContext().isActive) {
                try {
                    val claimed = streams.autoClaim(
                        topic = topic,
                        group = annotation.group,
                        consumer = annotation.consumer,
                        minIdleTime = Duration.ofMillis(annotation.minIdleTimeMs.coerceAtLeast(1)),
                        count = annotation.batchSize.coerceAtLeast(1),
                        startId = startId,
                    )
                    startId = claimed.nextStartId
                    if (claimed.messages.isNotEmpty()) {
                        logger.info(
                            "redis_stream_autoclaim_batch_received bean={} method={} topic={} group={} consumer={} count={} nextStartId={}",
                            handler.beanName,
                            handler.method.name,
                            topic.apiName,
                            annotation.group,
                            annotation.consumer,
                            claimed.messages.size,
                            claimed.nextStartId,
                        )
                    }
                    claimed.messages.forEach { dispatch(handler.bean, handler.method, annotation, it, claimed = true) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (error.isFatalStreamWorkerFailure()) throw error
                    if (!running.get() || !currentCoroutineContext().isActive) return@launch
                    logger.warn(
                        "redis_stream_autoclaim_failed bean={} method={} topic={} group={} consumer={} errorType={} error={}",
                        handler.beanName,
                        handler.method.name,
                        topic.apiName,
                        annotation.group,
                        annotation.consumer,
                        error.javaClass.name,
                        error.message,
                        error,
                    )
                }
                delay(annotation.fixedDelayMs.coerceAtLeast(1))
            }
        }
    }

    private suspend fun ensureRecoveryConsumer(handler: SchedulerHandler, topic: RedisStreamTopic) {
        val annotation = handler.annotation
        while (currentCoroutineContext().isActive) {
            try {
                streams.ensureConsumer(topic, annotation.group, annotation.consumer)
                logger.info(
                    "redis_stream_recovery_consumer_ready bean={} method={} topic={} group={} consumer={}",
                    handler.beanName,
                    handler.method.name,
                    topic.apiName,
                    annotation.group,
                    annotation.consumer,
                )
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error.isFatalStreamWorkerFailure()) throw error
                if (!running.get() || !currentCoroutineContext().isActive) return
                logger.error(
                    "redis_stream_recovery_consumer_create_failed bean={} method={} topic={} group={} consumer={} " +
                        "errorType={} error={}",
                    handler.beanName,
                    handler.method.name,
                    topic.apiName,
                    annotation.group,
                    annotation.consumer,
                    error.javaClass.name,
                    error.message,
                    error,
                )
                delay(RECOVERY_CONSUMER_CREATE_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun dispatch(
        bean: Any,
        method: RedisStreamHandlerMethod,
        annotation: StreamListener,
        message: RedisStreamMessage,
        claimed: Boolean,
    ) {
        dispatch(
            bean,
            method,
            annotation.eventType,
            annotation.payloadType.java,
            annotation.group,
            annotation.options,
            message,
            claimed,
        )
    }

    private suspend fun dispatch(
        bean: Any,
        method: RedisStreamHandlerMethod,
        annotation: StreamScheduler,
        message: RedisStreamMessage,
        claimed: Boolean,
    ) {
        dispatch(
            bean,
            method,
            annotation.eventType,
            annotation.payloadType.java,
            annotation.group,
            annotation.options,
            message,
            claimed,
        )
    }

    private suspend fun dispatch(
        bean: Any,
        method: RedisStreamHandlerMethod,
        eventType: String,
        payloadType: Class<*>,
        group: String,
        options: StreamOptions,
        message: RedisStreamMessage,
        claimed: Boolean,
    ) {
        dispatcher.dispatch(bean, method, eventType, payloadType, group, options, message, claimed)
    }

    private fun handlerMethod(beanName: String, method: Method, payloadType: Class<*>): RedisStreamHandlerMethod =
        RedisStreamHandlerMethod.create(beanName, method, payloadType)

    private fun isEnabled(property: String): Boolean =
        property.isBlank() || environment.getProperty(property, Boolean::class.java, true)

    private fun consumerName(prefix: String, workerIndex: Int): String =
        if (workerIndex == 0) prefix else "$prefix-${workerIndex + 1}"

    private fun readDeadlineMs(blockTimeMs: Long): Long =
        blockTimeMs + maxOf(MIN_READ_TIMEOUT_GRACE_MS, blockTimeMs / 2)

    private data class ListenerHandler(
        val beanName: String,
        val bean: Any,
        val method: RedisStreamHandlerMethod,
        val annotation: StreamListener,
    )

    private data class SchedulerHandler(
        val beanName: String,
        val bean: Any,
        val method: RedisStreamHandlerMethod,
        val annotation: StreamScheduler,
    )

    private companion object {
        const val MAX_CONCURRENCY = 32
        const val MIN_READ_TIMEOUT_GRACE_MS = 100L
        const val START_ID = "0-0"
        const val RECOVERY_CONSUMER_CREATE_RETRY_DELAY_MS = 5_000L
    }
}

internal fun Throwable.isFatalStreamWorkerFailure(): Boolean =
    this is VirtualMachineError || javaClass.name == "java.lang.ThreadDeath"

internal class RedisStreamHandlerMethod private constructor(
    private val function: KFunction<*>,
    private val acceptsContext: Boolean,
) {
    val name: String = function.name

    suspend fun invoke(bean: Any, payload: Any, context: StreamMessageContext) {
        val arguments = if (acceptsContext) arrayOf(bean, payload, context) else arrayOf(bean, payload)
        try {
            if (function.isSuspend) {
                function.callSuspend(*arguments)
            } else {
                function.call(*arguments)
            }
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    companion object {
        fun create(beanName: String, method: Method, payloadType: Class<*>): RedisStreamHandlerMethod {
            val function = requireNotNull(method.kotlinFunction) {
                "@StreamListener/@StreamScheduler method must be a Kotlin function: $beanName.${method.name}"
            }
            function.isAccessible = true
            val parameters = function.valueParameters
            require(parameters.size in 1..2) {
                "$beanName.${method.name} must accept payload and optional StreamMessageContext."
            }
            val acceptedPayloadType = parameters.first().type.jvmErasure.java
            require(acceptedPayloadType.isAssignableFrom(payloadType)) {
                "$beanName.${method.name} payload parameter ${acceptedPayloadType.name} does not accept ${payloadType.name}."
            }
            if (parameters.size == 2) {
                require(parameters[1].type.jvmErasure == StreamMessageContext::class) {
                    "$beanName.${method.name} second parameter must be StreamMessageContext."
                }
            }
            return RedisStreamHandlerMethod(function, parameters.size == 2)
        }
    }
}
