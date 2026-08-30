package com.buddystudy.backend.voice.adapter.outbound.storage

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingDownloadGrant
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingObjectMetadata
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingStoragePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingUploadGrant
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ChecksumMode
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.S3Error
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

@Component
@ConditionalOnExpression("'\${buddystudy.voice-tutor.recording-bucket:}' != ''")
class S3VoiceTutorRecordingStorageAdapter(
    properties: BuddyStudyProperties,
) : VoiceTutorRecordingStoragePort {
    private val configuration = properties.voiceTutor
    private val bucket = configuration.recordingBucket.trim()
    private val region = Region.of(configuration.recordingRegion.trim().ifEmpty { "ap-northeast-2" })
    private val s3 = S3Client.builder().region(region).build()
    private val presigner = S3Presigner.builder().region(region).build()
    private val versionDeleter = VoiceTutorRecordingVersionDeleter(s3, bucket)

    override suspend fun presignUpload(
        userId: Long,
        sessionId: String,
        contentType: String,
        byteSize: Long,
        sha256Hex: String,
        expiresAt: Instant,
    ): VoiceTutorRecordingUploadGrant = withContext(Dispatchers.IO) {
        val objectKey = voiceTutorRecordingObjectKey(userId, sessionId)
        val kmsKeyId = configuration.recordingKmsKeyId.trim()
        val request = voiceTutorRecordingPutRequest(
            bucket = bucket,
            objectKey = objectKey,
            contentType = contentType,
            byteSize = byteSize,
            sha256Hex = sha256Hex,
            kmsKeyId = kmsKeyId,
        )
        val duration = voiceTutorRecordingPresignDuration(Instant.now(), expiresAt)
        val signed = presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(duration)
                .putObjectRequest(request)
                .build(),
        )
        VoiceTutorRecordingUploadGrant(
            objectKey = objectKey,
            uploadUrl = signed.url().toString(),
            requiredHeaders = signed.signedHeaders()
                .filterKeys { !it.equals("host", ignoreCase = true) }
                .mapValues { (_, values) -> values.joinToString(",") },
            expiresAt = expiresAt,
        )
    }

    override suspend fun inspect(objectKey: String): VoiceTutorRecordingObjectMetadata? = withContext(Dispatchers.IO) {
        try {
            val response = s3.headObject(
                HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .checksumMode(ChecksumMode.ENABLED)
                    .build(),
            )
            VoiceTutorRecordingObjectMetadata(
                contentType = response.contentType().orEmpty(),
                byteSize = response.contentLength(),
                checksumSha256Base64 = response.checksumSHA256(),
            )
        } catch (error: S3Exception) {
            if (error.statusCode() == 404) null else throw error
        }
    }

    override suspend fun presignDownload(
        objectKey: String,
        sessionId: String,
        expiresAt: Instant,
    ): VoiceTutorRecordingDownloadGrant = withContext(Dispatchers.IO) {
        val duration = voiceTutorRecordingPresignDuration(Instant.now(), expiresAt)
        val request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .responseContentType(VOICE_TUTOR_RECORDING_CONTENT_TYPE)
            .responseContentDisposition("inline; filename=voice-tutor-$sessionId.m4a")
            .build()
        val signed = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(duration)
                .getObjectRequest(request)
                .build(),
        )
        VoiceTutorRecordingDownloadGrant(signed.url().toString(), expiresAt)
    }

    override suspend fun delete(objectKey: String) = withContext(Dispatchers.IO) {
        versionDeleter.deleteObject(objectKey)
    }

    override suspend fun deleteAll(userId: Long) = withContext(Dispatchers.IO) {
        versionDeleter.deletePrefix(voiceTutorRecordingUserPrefix(userId))
    }

    @PreDestroy
    fun close() {
        presigner.close()
        s3.close()
    }

}

internal fun voiceTutorRecordingPresignDuration(now: Instant, expiresAt: Instant): Duration {
    val requested = Duration.between(now, expiresAt)
    require(requested >= Duration.ofSeconds(MIN_VOICE_TUTOR_PRESIGN_SECONDS)) {
        "Voice Tutor recording presign expiry is too close."
    }
    return requested.coerceAtMost(Duration.ofSeconds(MAX_VOICE_TUTOR_PRESIGN_SECONDS))
}

private const val MIN_VOICE_TUTOR_PRESIGN_SECONDS = 1L
private const val MAX_VOICE_TUTOR_PRESIGN_SECONDS = 900L

internal class VoiceTutorRecordingVersionDeleter(
    private val s3: S3Client,
    private val bucket: String,
) {
    fun deleteObject(objectKey: String) {
        deleteMatching(prefix = objectKey) { it == objectKey }
    }

    fun deletePrefix(prefix: String) {
        deleteMatching(prefix) { it.startsWith(prefix) }
    }

    private fun deleteMatching(prefix: String, includes: (String) -> Boolean) {
        while (true) {
            val page = s3.listObjectVersions(
                ListObjectVersionsRequest.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .maxKeys(MAX_DELETE_OBJECTS)
                    .build(),
            )
            val objects = buildList {
                page.versions()
                    .filter { includes(it.key()) }
                    .forEach { add(versionIdentifier(it.key(), it.versionId())) }
                page.deleteMarkers()
                    .filter { includes(it.key()) }
                    .forEach { add(versionIdentifier(it.key(), it.versionId())) }
            }
            if (objects.isEmpty()) return
            objects.chunked(MAX_DELETE_OBJECTS).forEach { chunk ->
                val response = s3.deleteObjects(
                    DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(chunk).quiet(true).build())
                        .build(),
                )
                requireVoiceTutorDeleteSuccess(response.errors())
            }
            // Relist from the beginning after deletion so changing version markers
            // cannot skip a noncurrent version while the prefix is being drained.
        }
    }

    private fun versionIdentifier(key: String, versionId: String?): ObjectIdentifier {
        val builder = ObjectIdentifier.builder().key(key)
        if (!versionId.isNullOrBlank()) builder.versionId(versionId)
        return builder.build()
    }

    private companion object {
        const val MAX_DELETE_OBJECTS = 1_000
    }
}

internal const val VOICE_TUTOR_RECORDING_CONTENT_TYPE = "audio/mp4"

internal fun voiceTutorRecordingUserPrefix(userId: Long): String = "voice-tutor-recordings/$userId/"

internal fun voiceTutorRecordingObjectKey(userId: Long, sessionId: String): String =
    "${voiceTutorRecordingUserPrefix(userId)}$sessionId/recording.m4a"

internal fun sha256HexToBase64(value: String): String =
    Base64.getEncoder().encodeToString(HexFormat.of().parseHex(value))

internal fun voiceTutorRecordingPutRequest(
    bucket: String,
    objectKey: String,
    contentType: String,
    byteSize: Long,
    sha256Hex: String,
    kmsKeyId: String,
): PutObjectRequest {
    var request = PutObjectRequest.builder()
        .bucket(bucket)
        .key(objectKey)
        .contentType(contentType)
        .contentLength(byteSize)
        .ifNoneMatch("*")
        .checksumSHA256(sha256HexToBase64(sha256Hex))
    request = if (kmsKeyId.isEmpty()) {
        request.serverSideEncryption(ServerSideEncryption.AES256)
    } else {
        request.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId)
    }
    return request.build()
}

internal fun requireVoiceTutorDeleteSuccess(errors: List<S3Error>) {
    if (errors.isNotEmpty()) {
        error("Private Voice Tutor recording deletion was incomplete (failedObjects=${errors.size}).")
    }
}
