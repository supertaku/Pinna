package com.pinna.app.qr

sealed interface QrScanResult {
    data class Valid(val rawPayload: String) : QrScanResult
    data class Error(val message: String) : QrScanResult
    data object Ignored : QrScanResult
}

object QrScanResultMapper {
    fun map(rawValue: String?, nowEpochMillis: Long = System.currentTimeMillis()): QrScanResult {
        val raw = rawValue?.trim().orEmpty()
        if (raw.isBlank()) return QrScanResult.Ignored
        return when (val decoded = QrJoinPayloadCodec.decode(raw, nowEpochMillis)) {
            is QrDecodeResult.Valid -> QrScanResult.Valid(raw)
            QrDecodeResult.Expired -> QrScanResult.Error("This room is no longer available.")
            is QrDecodeResult.UnsupportedVersion -> QrScanResult.Error("This room uses an unsupported Pinna version.")
            is QrDecodeResult.Invalid -> QrScanResult.Ignored
        }
    }
}
