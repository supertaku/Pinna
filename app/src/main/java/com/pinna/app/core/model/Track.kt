package com.pinna.app.core.model

data class Track(
    val id: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val mimeType: String,
    val localUri: String,
    val sizeBytes: Long,
)
