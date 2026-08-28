package com.buddystudy.backend

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

class ReferralAttributionMigrationIntegrationTest {
    @Test
    fun `V96 backfills rewarded claims and preserves surviving rewards when inviter is deleted`() {
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.4")
            .withDatabaseName("buddystudy_referral_attribution_migration")
            .withUsername("buddystudy")
            .withPassword("buddystudy")

        mysql.start()
        try {
            flyway(mysql, target = "94").migrate()
            val seeded = mysql.connection().use(::seedReferral)

            val result = flyway(mysql, target = "96").migrate()

            mysql.connection().use { connection ->
                assertThat(result.migrationsExecuted).isEqualTo(2)
                assertThat(connection.stringValue(
                    "select status from referral_signup_attributions where referred_user_id = ${seeded.referredUserId}",
                )).isEqualTo("REWARDED")
                assertThat(connection.longValue(
                    "select inviter_user_id from referral_signup_attributions where referred_user_id = ${seeded.referredUserId}",
                )).isEqualTo(seeded.inviterUserId)

                connection.createStatement().use { statement ->
                    statement.executeUpdate("delete from users where id = ${seeded.inviterUserId}")
                }

                assertThat(connection.longValue("select count(*) from referrals where id = ${seeded.referralId}")).isEqualTo(1)
                assertThat(connection.nullableLongValue("select inviter_user_id from referrals where id = ${seeded.referralId}")).isNull()
                assertThat(connection.nullableLongValue(
                    "select inviter_user_id from referral_signup_attributions where referred_user_id = ${seeded.referredUserId}",
                )).isNull()
                assertThat(connection.longValue(
                    "select count(*) from referral_reward_grants where referral_id = ${seeded.referralId} and beneficiary_user_id = ${seeded.referredUserId}",
                )).isEqualTo(1)
                assertThat(connection.longValue(
                    "select count(*) from user_memberships where id = ${seeded.referredMembershipId}",
                )).isEqualTo(1)
            }
        } finally {
            mysql.stop()
        }
    }

    private fun flyway(mysql: MySQLContainer<*>, target: String): Flyway =
        Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration-mysql")
            .target(MigrationVersion.fromVersion(target))
            .load()

    private fun MySQLContainer<*>.connection(): Connection =
        DriverManager.getConnection(jdbcUrl, username, password)

    private fun seedReferral(connection: Connection): SeededReferral {
        val inviterUserId = connection.insertUser("inviter")
        val referredUserId = connection.insertUser("referred")
        val referralId = connection.insertReferral(inviterUserId, referredUserId)
        val inviterMembershipId = connection.insertReferralMembership(inviterUserId)
        val referredMembershipId = connection.insertReferralMembership(referredUserId)
        connection.insertRewardGrant(referralId, inviterUserId, inviterMembershipId)
        connection.insertRewardGrant(referralId, referredUserId, referredMembershipId)
        return SeededReferral(inviterUserId, referredUserId, referralId, referredMembershipId)
    }

    private fun Connection.insertUser(suffix: String): Long = generatedId(
        """
        insert into users (
            provider, provider_id, status, email, display_name, created_at, updated_at
        ) values (
            'EMAIL', 'referral-migration-$suffix', 'ACTIVE',
            'referral-migration-$suffix@example.com', 'Referral Migration $suffix',
            utc_timestamp(6), utc_timestamp(6)
        )
        """.trimIndent(),
    )

    private fun Connection.insertReferral(inviterUserId: Long, referredUserId: Long): Long = generatedId(
        """
        insert into referrals (
            inviter_user_id, referred_user_id, referral_code, redeemed_at, created_at, updated_at
        ) values (
            $inviterUserId, $referredUserId, 'BS-ABCDEFGH',
            utc_timestamp(6), utc_timestamp(6), utc_timestamp(6)
        )
        """.trimIndent(),
    )

    private fun Connection.insertReferralMembership(userId: Long): Long = generatedId(
        """
        insert into user_memberships (
            user_id, tier, status, source, started_at, expires_at, created_at, updated_at
        ) values (
            $userId, 'TIER2', 'ACTIVE', 'REFERRAL',
            utc_timestamp(6), date_add(utc_timestamp(6), interval 1 month), utc_timestamp(6), utc_timestamp(6)
        )
        """.trimIndent(),
    )

    private fun Connection.insertRewardGrant(referralId: Long, userId: Long, membershipId: Long) {
        createStatement().use { statement ->
            statement.executeUpdate(
                """
                insert into referral_reward_grants (
                    referral_id, beneficiary_user_id, membership_id, tier_code,
                    reward_months, starts_at, ends_at, created_at
                ) values (
                    $referralId, $userId, $membershipId, 'TIER2', 1,
                    utc_timestamp(6), date_add(utc_timestamp(6), interval 1 month), utc_timestamp(6)
                )
                """.trimIndent(),
            )
        }
    }

    private fun Connection.generatedId(sql: String): Long =
        createStatement().use { statement ->
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS)
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Expected a generated id." }
                keys.getLong(1)
            }
        }

    private fun Connection.longValue(sql: String): Long =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next()) { "Expected one row." }
                result.getLong(1)
            }
        }

    private fun Connection.nullableLongValue(sql: String): Long? =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next()) { "Expected one row." }
                result.getLong(1).takeUnless { result.wasNull() }
            }
        }

    private fun Connection.stringValue(sql: String): String =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next()) { "Expected one row." }
                result.getString(1)
            }
        }

    private data class SeededReferral(
        val inviterUserId: Long,
        val referredUserId: Long,
        val referralId: Long,
        val referredMembershipId: Long,
    )
}
