package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.model.TopicSubscriptionPolicy
import com.buddystudy.backend.community.application.port.outbound.TopicSubscriptionPort
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class TopicSubscriptionPersistenceAdapter(private val client: DatabaseClient) : TopicSubscriptionPort {
    override suspend fun findTopics(userId: Long): List<String> = client.sql(
        "select topic from user_topic_subscriptions where user_id = :userId order by sort_order, topic_key",
    ).bind("userId", userId).map { row, _ -> row.get("topic", String::class.java)!! }
        .all().collectList().awaitSingle()

    @Transactional
    override suspend fun replaceTopics(userId: Long, topics: List<String>) {
        // Lock the account even when its subscription set is empty. Concurrent PUTs cannot interleave.
        client.sql("select id from users where id = :userId for update").bind("userId", userId)
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
        client.sql("delete from user_topic_subscriptions where user_id = :userId").bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()
        topics.forEachIndexed { index, topic ->
            client.sql(
                "insert into user_topic_subscriptions (user_id, topic_key, topic, sort_order) " +
                    "values (:userId, :key, :topic, :position)",
            ).bind("userId", userId).bind("key", TopicSubscriptionPolicy.key(topic))
                .bind("topic", topic).bind("position", index).fetch().rowsUpdated().awaitSingle()
        }
    }
}
