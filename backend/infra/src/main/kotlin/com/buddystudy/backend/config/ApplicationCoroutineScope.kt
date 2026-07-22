package com.buddystudy.backend.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

@Component
class ApplicationCoroutineScope : CoroutineScope, DisposableBean {
    override val coroutineContext = SupervisorJob() + Dispatchers.Default

    override fun destroy() {
        cancel("Application is shutting down.")
    }
}
