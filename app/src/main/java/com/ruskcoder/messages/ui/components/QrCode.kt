package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code for arbitrary bytes.
 *
 * Bytes rather than a string, because what this app puts in a QR is a binary hardware identity and
 * not text. ZXing encodes from a `String`, so the payload is mapped one byte to one char through
 * ISO-8859-1 — the only encoding that survives that trip unchanged, since every byte 0x00–0xFF is a
 * valid code point in it and maps straight back. Passing the bytes through UTF-8 instead would
 * corrupt everything above 0x7F, which is most of an identity.
 *
 * Drawn onto a canvas rather than rendered to a bitmap so it stays sharp at any size and costs no
 * allocation beyond the matrix itself.
 */
@Composable
fun QrCode(
    payload: ByteArray,
    size: Dp,
    modifier: Modifier = Modifier,
    /**
     * The quiet zone and the light modules. Deliberately not theme-coloured: a scanner needs real
     * contrast, and a QR drawn in dark-theme greys is one many cameras will not read at all.
     */
    background: Color = Color.White,
    foreground: Color = Color.Black,
) {
    // Keyed on the content so a redraw — a theme change, a scroll — does not re-encode.
    val matrix = remember(payload) { encode(payload) } ?: return

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(12.dp),
    ) {
        Canvas(modifier = Modifier.size(size - 24.dp)) {
            // Whole pixels per module, so no module is drawn a fraction wider than its neighbour —
            // which is what makes an unevenly scaled QR fail to scan.
            val module = kotlin.math.floor(this.size.minDimension / matrix.width).coerceAtLeast(1f)
            val drawn = module * matrix.width
            val origin = Offset((this.size.width - drawn) / 2f, (this.size.height - drawn) / 2f)

            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (!matrix.get(x, y)) continue
                    drawRect(
                        color = foreground,
                        topLeft = Offset(origin.x + x * module, origin.y + y * module),
                        size = Size(module, module),
                    )
                }
            }
        }
    }
}

/**
 * Encode, or null when the payload will not fit.
 *
 * A QR tops out around 2,900 bytes at this correction level, and an identity that overruns it is a
 * legitimate outcome rather than a crash — the caller shows the text code instead.
 */
private fun encode(payload: ByteArray): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        String(payload.map { (it.toInt() and 0xFF).toChar() }.toCharArray()),
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            // Medium: enough redundancy to survive a phone camera at an angle without inflating a
            // payload this size into a module grid too fine to photograph.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            // The quiet zone is the padding around the canvas instead, in the right colour.
            EncodeHintType.MARGIN to 0,
        ),
    )
}.getOrNull()
