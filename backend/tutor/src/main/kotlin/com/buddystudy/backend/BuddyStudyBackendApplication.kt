package com.buddystudy.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@EntityScan("com.buddystudy")
@ImportRuntimeHints(HibernateLoggerRuntimeHints::class)
@SpringBootApplication(scanBasePackages = ["com.buddystudy"])
class BuddyStudyBackendApplication

fun main(args: Array<String>) {
    runApplication<BuddyStudyBackendApplication>(*args)
}

class HibernateLoggerRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hibernateGeneratedLoggers.forEach { logger ->
            hints.reflection().registerType(
                TypeReference.of(logger),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.INVOKE_DECLARED_METHODS,
            )
        }
        hibernateEventListenerArrays.forEach { listenerArray ->
            hints.reflection().registerType(TypeReference.of(listenerArray))
        }
        redisStreamJacksonTypes.forEach { type ->
            hints.reflection().registerType(
                TypeReference.of(type),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.DECLARED_FIELDS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.INVOKE_DECLARED_METHODS,
            )
        }
        hints.resources().registerPattern("org/hibernate/**/*.i18n.properties")
        hints.resources().registerPattern("db/migration/*.sql")
    }

    private val hibernateGeneratedLoggers = listOf(
        "org.hibernate.action.internal.ActionLogging_\$logger",
        "org.hibernate.boot.BootLogging_\$logger",
        "org.hibernate.boot.archive.scan.internal.ScannerLogger_\$logger",
        "org.hibernate.boot.beanvalidation.BeanValidationLogger_\$logger",
        "org.hibernate.boot.jaxb.JaxbLogger_\$logger",
        "org.hibernate.bytecode.enhance.internal.BytecodeEnhancementLogging_\$logger",
        "org.hibernate.bytecode.enhance.spi.interceptor.BytecodeInterceptorLogging_\$logger",
        "org.hibernate.cache.spi.SecondLevelCacheLogger_\$logger",
        "org.hibernate.collection.internal.CollectionLogger_\$logger",
        "org.hibernate.context.internal.CurrentSessionLogging_\$logger",
        "org.hibernate.dialect.DialectLogging_\$logger",
        "org.hibernate.engine.internal.NaturalIdLogging_\$logger",
        "org.hibernate.engine.internal.PersistenceContextLogging_\$logger",
        "org.hibernate.engine.internal.SessionMetricsLogger_\$logger",
        "org.hibernate.engine.internal.VersionLogger_\$logger",
        "org.hibernate.engine.jdbc.JdbcLogging_\$logger",
        "org.hibernate.engine.jdbc.batch.JdbcBatchLogging_\$logger",
        "org.hibernate.engine.jdbc.connections.internal.ConnectionProviderLogging_\$logger",
        "org.hibernate.engine.jdbc.env.internal.LobCreationLogging_\$logger",
        "org.hibernate.engine.jdbc.spi.SQLExceptionLogging_\$logger",
        "org.hibernate.event.internal.EntityCopyLogging_\$logger",
        "org.hibernate.event.internal.EventListenerLogging_\$logger",
        "org.hibernate.id.UUIDLogger_\$logger",
        "org.hibernate.id.enhanced.OptimizerLogger_\$logger",
        "org.hibernate.id.enhanced.SequenceGeneratorLogger_\$logger",
        "org.hibernate.id.enhanced.TableGeneratorLogger_\$logger",
        "org.hibernate.internal.CoreMessageLogger_\$logger",
        "org.hibernate.internal.SessionFactoryLogging_\$logger",
        "org.hibernate.internal.SessionFactoryRegistryMessageLogger_\$logger",
        "org.hibernate.internal.SessionLogging_\$logger",
        "org.hibernate.internal.log.ConnectionAccessLogger_\$logger",
        "org.hibernate.internal.log.ConnectionInfoLogger_\$logger",
        "org.hibernate.internal.log.DeprecationLogger_\$logger",
        "org.hibernate.internal.log.IncubationLogger_\$logger",
        "org.hibernate.internal.log.StatisticsLogger_\$logger",
        "org.hibernate.internal.log.UrlMessageBundle_\$logger",
        "org.hibernate.jpa.internal.JpaLogger_\$logger",
        "org.hibernate.loader.ast.internal.MultiKeyLoadLogging_\$logger",
        "org.hibernate.metamodel.mapping.MappingModelCreationLogging_\$logger",
        "org.hibernate.query.QueryLogging_\$logger",
        "org.hibernate.query.hql.HqlLogging_\$logger",
        "org.hibernate.resource.beans.internal.BeansMessageLogger_\$logger",
        "org.hibernate.resource.jdbc.internal.LogicalConnectionLogging_\$logger",
        "org.hibernate.resource.jdbc.internal.ResourceRegistryLogger_\$logger",
        "org.hibernate.resource.transaction.backend.jta.internal.JtaLogging_\$logger",
        "org.hibernate.resource.transaction.internal.SynchronizationLogging_\$logger",
        "org.hibernate.service.internal.ServiceLogger_\$logger",
        "org.hibernate.sql.ast.tree.SqlAstTreeLogger_\$logger",
        "org.hibernate.sql.exec.SqlExecLogger_\$logger",
        "org.hibernate.sql.model.ModelMutationLogging_\$logger",
        "org.hibernate.sql.results.LoadingLogger_\$logger",
        "org.hibernate.sql.results.ResultsLogger_\$logger",
        "org.hibernate.sql.results.graph.embeddable.EmbeddableLoadingLogger_\$logger",
    )

    private val hibernateEventListenerArrays = listOf(
        "org.hibernate.event.spi.AutoFlushEventListener[]",
        "org.hibernate.event.spi.ClearEventListener[]",
        "org.hibernate.event.spi.DeleteEventListener[]",
        "org.hibernate.event.spi.DirtyCheckEventListener[]",
        "org.hibernate.event.spi.EvictEventListener[]",
        "org.hibernate.event.spi.FlushEntityEventListener[]",
        "org.hibernate.event.spi.FlushEventListener[]",
        "org.hibernate.event.spi.InitializeCollectionEventListener[]",
        "org.hibernate.event.spi.LoadEventListener[]",
        "org.hibernate.event.spi.LockEventListener[]",
        "org.hibernate.event.spi.MergeEventListener[]",
        "org.hibernate.event.spi.PersistEventListener[]",
        "org.hibernate.event.spi.PostActionEventListener[]",
        "org.hibernate.event.spi.PostCollectionRecreateEventListener[]",
        "org.hibernate.event.spi.PostCollectionRemoveEventListener[]",
        "org.hibernate.event.spi.PostCollectionUpdateEventListener[]",
        "org.hibernate.event.spi.PostCommitDeleteEventListener[]",
        "org.hibernate.event.spi.PostCommitInsertEventListener[]",
        "org.hibernate.event.spi.PostCommitUpdateEventListener[]",
        "org.hibernate.event.spi.PostDeleteEventListener[]",
        "org.hibernate.event.spi.PostInsertEventListener[]",
        "org.hibernate.event.spi.PostLoadEventListener[]",
        "org.hibernate.event.spi.PostUpdateEventListener[]",
        "org.hibernate.event.spi.PostUpsertEventListener[]",
        "org.hibernate.event.spi.PreCollectionRecreateEventListener[]",
        "org.hibernate.event.spi.PreCollectionRemoveEventListener[]",
        "org.hibernate.event.spi.PreCollectionUpdateEventListener[]",
        "org.hibernate.event.spi.PreDeleteEventListener[]",
        "org.hibernate.event.spi.PreFlushEventListener[]",
        "org.hibernate.event.spi.PreInsertEventListener[]",
        "org.hibernate.event.spi.PreLoadEventListener[]",
        "org.hibernate.event.spi.PreUpdateEventListener[]",
        "org.hibernate.event.spi.PreUpsertEventListener[]",
        "org.hibernate.event.spi.RefreshEventListener[]",
        "org.hibernate.event.spi.ReplicateEventListener[]",
    )

    private val redisStreamJacksonTypes = listOf(
        "com.redisstream.consumer.ProducerRoutingResponse",
        "com.redisstream.consumer.ProducerRoutingShard",
    )
}
