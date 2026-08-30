package com.buddystudy.backend.voice.adapter.outbound.storage

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse
import software.amazon.awssdk.services.s3.model.ObjectVersion
import software.amazon.awssdk.services.s3.model.S3Error
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import java.time.Duration
import java.time.Instant

class S3VoiceTutorRecordingStorageAdapterTest {
    @Test
    fun `presign duration is never rounded up beyond the requested expiry`() {
        val now = Instant.parse("2026-08-31T00:00:00Z")

        assertThat(voiceTutorRecordingPresignDuration(now, now.plusMillis(1_500)))
            .isEqualTo(Duration.ofMillis(1_500))
        assertThat(voiceTutorRecordingPresignDuration(now, now.plusSeconds(1_000)))
            .isEqualTo(Duration.ofSeconds(900))
        assertThatThrownBy {
            voiceTutorRecordingPresignDuration(now, now.plusMillis(999))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `object keys are private deterministic and isolated by owner`() {
        val first = voiceTutorRecordingObjectKey(7, "00000000-0000-4000-8000-000000000007")
        val secondOwner = voiceTutorRecordingObjectKey(70, "00000000-0000-4000-8000-000000000007")

        assertThat(first).isEqualTo(
            "voice-tutor-recordings/7/00000000-0000-4000-8000-000000000007/recording.m4a",
        )
        assertThat(first).startsWith(voiceTutorRecordingUserPrefix(7))
        assertThat(secondOwner).startsWith(voiceTutorRecordingUserPrefix(70))
        assertThat(secondOwner).doesNotStartWith(voiceTutorRecordingUserPrefix(7))
    }

    @Test
    fun `sha256 hex is converted to the checksum encoding required by S3`() {
        assertThat(sha256HexToBase64("00".repeat(32)))
            .isEqualTo("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    }

    @Test
    fun `upload contract is non overwritable checksum bound and encrypted`() {
        val request = voiceTutorRecordingPutRequest(
            bucket = "private-recordings",
            objectKey = "voice-tutor-recordings/7/session/recording.m4a",
            contentType = "audio/mp4",
            byteSize = 1_024,
            sha256Hex = "ab".repeat(32),
            kmsKeyId = "kms-key",
        )

        assertThat(request.ifNoneMatch()).isEqualTo("*")
        assertThat(request.contentType()).isEqualTo("audio/mp4")
        assertThat(request.contentLength()).isEqualTo(1_024L)
        assertThat(request.checksumSHA256()).isEqualTo(sha256HexToBase64("ab".repeat(32)))
        assertThat(request.serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS)
        assertThat(request.ssekmsKeyId()).isEqualTo("kms-key")
    }

    @Test
    fun `partial multi object deletion fails so account cleanup can retry`() {
        val failure = runCatching {
            requireVoiceTutorDeleteSuccess(
                listOf(S3Error.builder().code("AccessDenied").message("denied").build()),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).contains("failedObjects=1")
        assertThat(failure?.message).doesNotContain("AccessDenied", "denied")
    }

    @Test
    fun `single recording deletion removes every version and delete marker for only that key`() {
        val s3 = mock(S3Client::class.java)
        val objectKey = "voice-tutor-recordings/7/session/recording.m4a"
        `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
            ListObjectVersionsResponse.builder()
                .versions(
                    version(objectKey, "current-version"),
                    version(objectKey, "noncurrent-version"),
                    version("$objectKey.unrelated", "unrelated-version"),
                )
                .deleteMarkers(marker(objectKey, "delete-marker"))
                .build(),
            ListObjectVersionsResponse.builder().build(),
        )
        `when`(s3.deleteObjects(any(DeleteObjectsRequest::class.java)))
            .thenReturn(DeleteObjectsResponse.builder().build())

        VoiceTutorRecordingVersionDeleter(s3, "private-recordings").deleteObject(objectKey)

        val listRequests = ArgumentCaptor.forClass(ListObjectVersionsRequest::class.java)
        verify(s3, times(2)).listObjectVersions(listRequests.capture())
        assertThat(listRequests.allValues).allSatisfy {
            assertThat(it.bucket()).isEqualTo("private-recordings")
            assertThat(it.prefix()).isEqualTo(objectKey)
        }
        val deleteRequest = ArgumentCaptor.forClass(DeleteObjectsRequest::class.java)
        verify(s3).deleteObjects(deleteRequest.capture())
        assertThat(deleteRequest.value.delete().objects().map { it.key() to it.versionId() })
            .containsExactlyInAnyOrder(
                objectKey to "current-version",
                objectKey to "noncurrent-version",
                objectKey to "delete-marker",
            )
    }

    @Test
    fun `owner prefix deletion relists until all version pages and delete markers are gone`() {
        val s3 = mock(S3Client::class.java)
        val prefix = voiceTutorRecordingUserPrefix(7)
        `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
            ListObjectVersionsResponse.builder()
                .versions(version("${prefix}first/recording.m4a", "first-current"))
                .deleteMarkers(marker("${prefix}first/recording.m4a", "first-marker"))
                .build(),
            ListObjectVersionsResponse.builder()
                .versions(version("${prefix}second/recording.m4a", "second-noncurrent"))
                .build(),
            ListObjectVersionsResponse.builder().build(),
        )
        `when`(s3.deleteObjects(any(DeleteObjectsRequest::class.java)))
            .thenReturn(DeleteObjectsResponse.builder().build())

        VoiceTutorRecordingVersionDeleter(s3, "private-recordings").deletePrefix(prefix)

        verify(s3, times(3)).listObjectVersions(any(ListObjectVersionsRequest::class.java))
        val deleteRequests = ArgumentCaptor.forClass(DeleteObjectsRequest::class.java)
        verify(s3, times(2)).deleteObjects(deleteRequests.capture())
        assertThat(deleteRequests.allValues.flatMap { it.delete().objects() }.map { it.key() to it.versionId() })
            .containsExactlyInAnyOrder(
                "${prefix}first/recording.m4a" to "first-current",
                "${prefix}first/recording.m4a" to "first-marker",
                "${prefix}second/recording.m4a" to "second-noncurrent",
            )
    }

    private fun version(key: String, versionId: String) =
        ObjectVersion.builder().key(key).versionId(versionId).build()

    private fun marker(key: String, versionId: String) =
        DeleteMarkerEntry.builder().key(key).versionId(versionId).build()
}
