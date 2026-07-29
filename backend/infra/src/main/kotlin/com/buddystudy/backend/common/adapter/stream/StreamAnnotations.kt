package com.buddystudy.backend.common.adapter.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import org.springframework.aot.hint.BindingReflectionHintsRegistrar
import org.springframework.aot.hint.ExecutableMode
import org.springframework.aot.hint.ReflectionHints
import org.springframework.aot.hint.annotation.Reflective
import org.springframework.aot.hint.annotation.ReflectiveProcessor
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import kotlin.reflect.KClass

enum class StreamOptions {
    NONE,
    ACK,
    ACK_DEL,
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Reflective(StreamReflectiveProcessor::class)
annotation class StreamListener(
    val topic: RedisStreamTopic,
    val legacyTopic: RedisStreamTopic = RedisStreamTopic.NONE,
    val group: String,
    val consumer: String,
    val eventType: String,
    val payloadType: KClass<*>,
    val batchSize: Long = 10,
    val blockTimeMs: Long = 1_000,
    val pollDelayMs: Long = 1_000,
    val concurrency: Int = 1,
    val concurrencyProperty: String = "",
    val enabledProperty: String = "",
    val options: StreamOptions = StreamOptions.ACK,
)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Reflective(StreamReflectiveProcessor::class)
annotation class StreamScheduler(
    val topic: RedisStreamTopic,
    val legacyTopic: RedisStreamTopic = RedisStreamTopic.NONE,
    val group: String,
    val consumer: String,
    val eventType: String,
    val payloadType: KClass<*>,
    val batchSize: Long = 10,
    val minIdleTimeMs: Long,
    val fixedDelayMs: Long,
    val initialDelayMs: Long = 0,
    val enabledProperty: String = "",
    val options: StreamOptions = StreamOptions.ACK,
)

data class StreamMessageContext(
    val streamKey: String,
    val recordId: String,
    val eventId: String?,
    val eventType: String,
    val fields: Map<String, String>,
    val claimed: Boolean,
)

class StreamReflectiveProcessor : ReflectiveProcessor {
    override fun registerReflectionHints(hints: ReflectionHints, element: AnnotatedElement) {
        if (element !is Method) return
        hints.registerMethod(element, ExecutableMode.INVOKE)
        val payloadType = element.getAnnotation(StreamListener::class.java)?.payloadType?.java
            ?: element.getAnnotation(StreamScheduler::class.java)?.payloadType?.java
            ?: return
        BindingReflectionHintsRegistrar().registerReflectionHints(hints, payloadType)
    }
}
