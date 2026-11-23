package com.example.callbridge.security

import android.graphics.Bitmap
import android.graphics.Color
import com.example.callbridge.bluetooth.BleConstants
import com.example.callbridge.core.AppConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONException
import org.json.JSONObject

/**
 * Structured one-time QR pairing payload per BRIDGELINK_PROTOCOL.md.
 * Contains ONLY required bootstrap metadata (never sensitive user data).
 */
data class PairingQrPayload(
    val version: Int = AppConfig.PROTOCOL_VERSION,
    val deviceId: String,
    val deviceName: String,
    val token: String,
    val pinCode: String,
    val certSha256: String,
    val wssHost: String?,
    val wssPort: Int = AppConfig.DEFAULT_WSS_PORT,
    val bleServiceUuid: String = BleConstants.SERVICE_UUID.toString(),
    val expiresAtMs: Long
) {
    fun toJsonString(): String = JSONObject().apply {
        put("v", version)
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("token", token)
        put("pinCode", pinCode)
        put("certSha256", certSha256)
        put("wssHost", wssHost ?: JSONObject.NULL)
        put("wssPort", wssPort)
        put("bleService", bleServiceUuid)
        put("expiresAt", expiresAtMs)
    }.toString()

    companion object {
        fun fromJsonString(raw: String): PairingQrPayload? = try {
            val json = JSONObject(raw)
            val host = if (json.has("wssHost") && !json.isNull("wssHost")) {
                json.optString("wssHost").takeIf { it.isNotBlank() }
            } else null
            PairingQrPayload(
                version = json.getInt("v"),
                deviceId = json.getString("deviceId"),
                deviceName = json.optString("deviceName", "BridgeLink Android"),
                token = json.getString("token"),
                pinCode = json.optString("pinCode", ""),
                certSha256 = json.getString("certSha256"),
                wssHost = host,
                wssPort = json.optInt("wssPort", AppConfig.DEFAULT_WSS_PORT),
                bleServiceUuid = json.optString("bleService", BleConstants.SERVICE_UUID.toString()),
                expiresAtMs = json.getLong("expiresAt")
            )
        } catch (_: JSONException) {
            null
        }
    }
}

/**
 * Renders crisp high-contrast QR code Bitmaps locally using ZXing core (zero camera permissions).
 */
object PairingQrGenerator {

    fun renderQrBitmap(
        content: String,
        sizePx: Int = 512,
        foregroundColor: Int = Color.parseColor("#0D1B2A"),
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        return runCatching {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val rowOffset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[rowOffset + x] = if (matrix.get(x, y)) foregroundColor else backgroundColor
                }
            }
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        }.getOrNull()
    }
}
