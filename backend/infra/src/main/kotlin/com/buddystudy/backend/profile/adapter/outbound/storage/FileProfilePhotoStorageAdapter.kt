package com.buddystudy.backend.profile.adapter.outbound.storage

import com.buddystudy.backend.profile.application.port.outbound.ProfilePhotoStoragePort
import com.buddystudy.backend.profile.application.port.outbound.StoredProfilePhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Component
class FileProfilePhotoStorageAdapter(
    @param:Value("\${buddystudy.profile-photo.directory:/app/profile-photos}")
    private val directory: String,
    @param:Value("\${buddystudy.profile-photo.public-base-url:https://api.ghkdqhrbals.org}")
    private val publicBaseUrl: String,
) : ProfilePhotoStoragePort {
    override suspend fun save(userId: Long, contentType: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val root = Path.of(directory)
        Files.createDirectories(root)
        val destination = root.resolve("$userId.photo")
        val temporary = root.resolve(".$userId.photo.tmp")
        Files.write(temporary, bytes)
        try {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        Files.writeString(root.resolve("$userId.content-type"), contentType)
        "${publicBaseUrl.trimEnd('/')}/api/v1/profile/photo/$userId?v=${System.currentTimeMillis()}"
    }

    override suspend fun load(userId: Long): StoredProfilePhoto? = withContext(Dispatchers.IO) {
        val root = Path.of(directory)
        val photo = root.resolve("$userId.photo")
        if (!Files.isRegularFile(photo)) return@withContext null
        val contentTypePath = root.resolve("$userId.content-type")
        val contentType = if (Files.isRegularFile(contentTypePath)) {
            Files.readString(contentTypePath).trim()
        } else {
            "image/jpeg"
        }
        StoredProfilePhoto(contentType = contentType, bytes = Files.readAllBytes(photo))
    }

    override suspend fun delete(userId: Long) = withContext(Dispatchers.IO) {
        val root = Path.of(directory)
        Files.deleteIfExists(root.resolve("$userId.photo"))
        Files.deleteIfExists(root.resolve("$userId.content-type"))
        Unit
    }
}
