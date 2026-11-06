package com.toshi0907.uploadsrv

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private const val TAG = "QrCode"

/** 規格上必要な余白(クワイエットゾーン)のモジュール数。 */
private const val QUIET_ZONE_MODULES = 4

/** 文字列をQRコードのモジュール行列に変換する。余白は含めない。失敗時は null。 */
private fun encodeQr(text: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
        ),
    )
}.onFailure { Log.w(TAG, "QRコードの生成に失敗しました", it) }.getOrNull()

/** 黒モジュールを1×1単位の矩形として集めたパス。描画時に拡大する。 */
private fun BitMatrix.toPath(): Path = Path().apply {
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (this@toPath[x, y]) addRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f))
        }
    }
}

/**
 * [text] のQRコードを表示する。
 * ダークテーマでも読み取れるよう、白地に黒で描画し4モジュール分の余白を付ける。
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, size: Dp = 232.dp) {
    val qr = remember(text) { encodeQr(text)?.let { it.width to it.toPath() } }
    if (qr == null) {
        Text(text = "QRコードを生成できませんでした", color = MaterialTheme.colorScheme.error)
        return
    }
    val (modules, path) = qr
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "$text のQRコード" },
    ) {
        drawRect(Color.White)
        // モジュール境界がピクセル境界に揃うよう整数ピクセルにし、余りは中央寄せで吸収する
        val total = modules + QUIET_ZONE_MODULES * 2
        val cell = (this.size.minDimension / total).toInt().coerceAtLeast(1).toFloat()
        val origin = Offset(
            (this.size.width - cell * modules) / 2f,
            (this.size.height - cell * modules) / 2f,
        ).let { Offset(it.x.toInt().toFloat(), it.y.toInt().toFloat()) }
        translate(origin.x, origin.y) {
            scale(scale = cell, pivot = Offset.Zero) { drawPath(path, Color.Black) }
        }
    }
}
