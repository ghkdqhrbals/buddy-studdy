package com.buddystudy.backend.common.application.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule

object JsonMapperProvider {
    val mapper: ObjectMapper = ObjectMapper()
        .registerModules(
            KotlinModule.Builder().build(),
            JavaTimeModule(),
        )
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
