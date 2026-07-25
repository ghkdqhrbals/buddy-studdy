package com.buddystudy.backend.profile.application.port.outbound

data class StoredProfilePhoto(
    val contentType: String,
    val bytes: ByteArray,
)

interface ProfilePhotoStoragePort {
    suspend fun save(userId: Long, contentType: String, bytes: ByteArray): String
    suspend fun load(userId: Long): StoredProfilePhoto?
    suspend fun delete(userId: Long)
}

object UnavailableProfilePhotoStoragePort : ProfilePhotoStoragePort {
    override suspend fun save(userId: Long, contentType: String, bytes: ByteArray): String =
        error("Profile photo storage is not configured.")

    override suspend fun load(userId: Long): StoredProfilePhoto? = null

    override suspend fun delete(userId: Long) = Unit
}
