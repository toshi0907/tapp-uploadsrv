package com.toshi0907.uploadsrv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 文字列をQRコードのモジュール行列に変換する。余白(クワイエットゾーン)は含めない。 */
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
}.getOrNull()

/**
 * [text] のQRコードを表示する。
 * ダークテーマでも読み取れるよう、白地に黒で描画し周囲に余白を付ける。
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val matrix = remember(text) { encodeQr(text) } ?: return
    Canvas(
        modifier = modifier
            .background(Color.White)
            .padding(16.dp)
            .size(size)
            .semantics { contentDescription = "$text のQRコード" },
    ) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    // 隣接セル間に隙間が出ないよう、わずかに大きく描く
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}
