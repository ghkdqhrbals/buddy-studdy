package com.buddystudy.backend.common.adapter.outbound.persistence

import org.springframework.r2dbc.core.DatabaseClient

internal fun indexedBindMarkers(prefix: String, size: Int): String {
    require(size > 0) { "At least one value is required for an indexed SQL binding." }
    return (0 until size).joinToString(", ") { ":$prefix$it" }
}

internal fun <T : Any> DatabaseClient.GenericExecuteSpec.bindIndexed(
    prefix: String,
    values: Collection<T>,
): DatabaseClient.GenericExecuteSpec {
    var spec = this
    values.forEachIndexed { index, value -> spec = spec.bind("$prefix$index", value) }
    return spec
}
