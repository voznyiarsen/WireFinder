package com.wirefinder.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitArray
import com.google.zxing.qrcode.QRCodeWriter

object QrCode {

    fun generate(content: String, sizePx: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val pixels = IntArray(sizePx * sizePx)
        val row = BitArray(sizePx)
        var i = 0
        for (y in 0 until sizePx) {
            matrix.getRow(y, row)
            for (x in 0 until sizePx) {
                pixels[i++] = if (row[x]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    fun wifiQrString(ssid: String, password: String): String {
        val security = if (password.isEmpty()) "nopass" else "WPA"
        return "WIFI:T:$security;S:${escape(ssid)};P:${escape(password)};;"
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(":", "\\:")
        .replace(",", "\\,")
        .replace("\"", "\\\"")
}
