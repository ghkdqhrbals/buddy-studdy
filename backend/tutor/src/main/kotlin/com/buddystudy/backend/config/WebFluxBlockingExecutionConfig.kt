package com.buddystudy.backend.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.reactive.config.BlockingExecutionConfigurer
import org.springframework.web.reactive.config.WebFluxConfigurer
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.ThreadPoolExecutor

@ConfigurationProperties("buddystudy.webflux.blocking")
data class WebFluxBlockingProperties(
    var coreSize: Int = 8,
    var maxSize: Int = 16,
    var queueCapacity: Int = 64,
    var keepAliveSeconds: Int = 60,
) {
    fun validate() {
        require(coreSize > 0) { "WebFlux blocking core size must be positive." }
        require(maxSize >= coreSize) { "WebFlux blocking max size must be at least the core size." }
        require(queueCapacity > 0) { "WebFlux blocking queue capacity must be positive." }
        require(keepAliveSeconds > 0) { "WebFlux blocking keep-alive must be positive." }
    }
}

@Configuration
@EnableConfigurationProperties(WebFluxBlockingProperties::class)
class WebFluxBlockingExecutionConfig(
    private val properties: WebFluxBlockingProperties,
) : WebFluxConfigurer {
    @Bean("webFluxBlockingExecutor")
    fun webFluxBlockingExecutor(): AsyncTaskExecutor {
        properties.validate()
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = properties.coreSize
            maxPoolSize = properties.maxSize
            queueCapacity = properties.queueCapacity
            setKeepAliveSeconds(properties.keepAliveSeconds)
            setThreadNamePrefix("webflux-blocking-")
            setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
            initialize()
        }
    }

    @Bean("webFluxBlockingScheduler")
    fun webFluxBlockingScheduler(
        @Qualifier("webFluxBlockingExecutor") executor: AsyncTaskExecutor,
    ): Scheduler = Schedulers.fromExecutor(executor)

    override fun configureBlockingExecution(configurer: BlockingExecutionConfigurer) {
        configurer.setExecutor(webFluxBlockingExecutor())
    }
}
