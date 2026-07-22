package com.buddystudy.backend.config

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Query

suspend fun <T : Any> R2dbcEntityTemplate.selectPage(
    query: Query,
    countQuery: Query,
    type: Class<T>,
    pageable: Pageable,
): Page<T> = coroutineScope {
    val content = async {
        select(query.limit(pageable.pageSize).offset(pageable.offset), type).collectList().awaitSingle()
    }
    val total = async { count(countQuery, type).awaitSingle() }
    PageImpl(content.await(), pageable, total.await())
}

suspend fun <T : Any> R2dbcEntityTemplate.saveEntity(entity: T, id: Long): T =
    if (id == 0L) insert(entity).awaitSingle() else update(entity).awaitSingle()
