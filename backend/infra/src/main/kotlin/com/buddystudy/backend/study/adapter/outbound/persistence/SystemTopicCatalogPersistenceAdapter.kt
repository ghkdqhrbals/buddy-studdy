package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.study.application.port.outbound.SystemTopicCatalogCandidate
import com.buddystudy.backend.study.application.port.outbound.SystemTopicCatalogPort
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant

@Component
class SystemTopicCatalogPersistenceAdapter(
    private val client: DatabaseClient,
) : SystemTopicCatalogPort {
    override suspend fun findChildren(
        rootTopicKey: String,
        parentPathKey: String,
        language: String,
        depth: Int,
        limit: Int,
    ): List<SystemTopicCatalogCandidate> =
        client.sql(
            """
            select topic, sort_order
            from system_topic_catalog
            where root_topic_hash = :rootTopicHash
              and parent_path_hash = :parentPathHash
              and language = :language
              and depth = :depth
            order by sort_order asc, id asc
            limit :limit
            """.trimIndent(),
        )
            .bind("rootTopicHash", rootTopicKey.sha256())
            .bind("parentPathHash", parentPathKey.sha256())
            .bind("language", language)
            .bind("depth", depth)
            .bind("limit", limit.coerceIn(1, 10))
            .map { row, _ ->
                SystemTopicCatalogCandidate(
                    topic = row.get("topic", String::class.java)!!,
                    sortOrder = row.get("sort_order", java.lang.Integer::class.java)!!.toInt(),
                )
            }
            .all()
            .collectList()
            .awaitSingle()

    @Transactional
    override suspend fun saveChildren(
        rootTopicKey: String,
        parentPathKey: String,
        language: String,
        depth: Int,
        topics: List<String>,
        now: Instant,
    ) {
        topics.take(10).forEachIndexed { index, topic ->
            client.sql(
                """
                insert into system_topic_catalog (
                    root_topic_key, root_topic_hash, parent_path_key, parent_path_hash,
                    topic_key, language, depth,
                    topic, sort_order, created_at, updated_at
                ) values (
                    :rootTopicKey, :rootTopicHash, :parentPathKey, :parentPathHash,
                    :topicKey, :language, :depth,
                    :topic, :sortOrder, :now, :now
                )
                on duplicate key update
                    topic = values(topic),
                    sort_order = least(sort_order, values(sort_order)),
                    updated_at = values(updated_at)
                """.trimIndent(),
            )
                .bind("rootTopicKey", rootTopicKey)
                .bind("rootTopicHash", rootTopicKey.sha256())
                .bind("parentPathKey", parentPathKey)
                .bind("parentPathHash", parentPathKey.sha256())
                .bind("topicKey", topic.normalizedCatalogTopicKey())
                .bind("language", language)
                .bind("depth", depth)
                .bind("topic", topic)
                .bind("sortOrder", index)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }
}

private fun String.normalizedCatalogTopicKey(): String =
    trim()
        .lowercase()
        .replace(Regex("[\\s_-]+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
