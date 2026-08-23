package com.buddystudy.backend.appupdate.adapter.outbound.firebase

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.Parameter
import com.google.firebase.remoteconfig.ParameterValue
import com.buddystudy.backend.appupdate.application.model.AppControlRemotePolicy
import com.buddystudy.backend.appupdate.application.model.RemoteConfigPublicationResult
import com.buddystudy.backend.appupdate.application.port.outbound.AppControlRemoteConfigPort
import com.buddystudy.backend.externalapi.adapter.outbound.history.ExternalApiHistoryRecorder
import com.buddystudy.backend.externalapi.adapter.outbound.history.ExternalApiRequest
import com.buddystudy.backend.externalapi.adapter.outbound.history.ExternalApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Base64

@Component
class FirebaseAppControlRemoteConfigAdapter(
    private val objectMapper: ObjectMapper,
    @param:Value("\${buddystudy.firebase.remote-config.project-id:}")
    private val projectId: String,
    @param:Value("\${buddystudy.firebase.remote-config.service-account-json-base64:}")
    private val serviceAccountJsonBase64: String,
    @param:Value("\${buddystudy.firebase.remote-config.parameter-key:ios_app_control_v1}")
    private val parameterKey: String,
    private val history: ExternalApiHistoryRecorder,
) : AppControlRemoteConfigPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun publish(policy: AppControlRemotePolicy): RemoteConfigPublicationResult =
        try {
            withContext(Dispatchers.IO) {
                val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/${projectId.trim()}/remoteConfig"
                val remoteConfig = FirebaseRemoteConfig.getInstance(firebaseApp())
                val template = history.record(
                    ExternalApiRequest(
                        provider = "firebase",
                        operation = "get-remote-config",
                        method = "GET",
                        url = url,
                    ),
                ) {
                    val current = remoteConfig.getTemplateAsync().get()
                    ExternalApiResponse(
                        value = current,
                        body = objectMapper.writeValueAsString(templateSnapshot(current)),
                    )
                }
                val parameters = template.parameters.toMutableMap()
                parameters[parameterKey] = Parameter()
                    .setDefaultValue(ParameterValue.of(objectMapper.writeValueAsString(policy)))
                template.parameters = parameters
                val requestBody = objectMapper.writeValueAsString(templateSnapshot(template))
                val validated = history.record(
                    ExternalApiRequest("firebase", "validate-remote-config", "POST", "$url:validate", body = requestBody),
                ) {
                    val result = remoteConfig.validateTemplateAsync(template).get()
                    ExternalApiResponse(result, body = objectMapper.writeValueAsString(templateSnapshot(result)))
                }
                history.record(
                    ExternalApiRequest("firebase", "publish-remote-config", "PUT", url, body = requestBody),
                ) {
                    val published = remoteConfig.publishTemplateAsync(validated).get()
                    ExternalApiResponse(Unit, body = objectMapper.writeValueAsString(templateSnapshot(published)))
                }
                val publishedAt = Instant.now()
                logger.info(
                    "firebase_remote_config_published parameterKey={} policyId={} revision={}",
                    parameterKey,
                    policy.policyId,
                    policy.revision,
                )
                RemoteConfigPublicationResult(policy.revision, publishedAt)
            }
        } catch (error: Exception) {
            logger.error(
                "firebase_remote_config_publish_failed parameterKey={} policyId={} revision={}",
                parameterKey,
                policy.policyId,
                policy.revision,
                error,
            )
            throw error
        }

    private fun firebaseApp(): FirebaseApp {
        val normalizedProjectId = projectId.trim()
        val encodedCredentials = serviceAccountJsonBase64.trim()
        check(normalizedProjectId.isNotEmpty()) {
            "FIREBASE_PROJECT_ID is required to publish app control policy."
        }
        check(encodedCredentials.isNotEmpty()) {
            "FIREBASE_SERVICE_ACCOUNT_JSON_BASE64 is required to publish app control policy."
        }
        return synchronized(firebaseAppsLock) {
            FirebaseApp.getApps()
                .firstOrNull { it.name == FIREBASE_APP_NAME }
                ?: FirebaseApp.initializeApp(
                    FirebaseOptions.builder()
                        .setProjectId(normalizedProjectId)
                        .setCredentials(
                            GoogleCredentials.fromStream(
                                ByteArrayInputStream(Base64.getDecoder().decode(encodedCredentials)),
                            ),
                        )
                        .build(),
                    FIREBASE_APP_NAME,
                )
        }
    }

    private fun templateSnapshot(template: com.google.firebase.remoteconfig.Template): Map<String, Any?> = mapOf(
        "etag" to template.getETag(),
        "parameters" to template.parameters,
        "parameterGroups" to template.parameterGroups,
        "version" to template.version,
    )

    private companion object {
        const val FIREBASE_APP_NAME = "buddystudy-remote-config"
        val firebaseAppsLock = Any()
    }
}
