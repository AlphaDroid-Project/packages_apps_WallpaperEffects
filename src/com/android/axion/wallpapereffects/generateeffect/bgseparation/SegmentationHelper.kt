/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.axion.wallpapereffects.generateeffect.bgseparation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

object SegmentationHelper {

    suspend fun getMaskedFGColors(
        fgColorsTensorBuffer: TensorBuffer,
        maskTensorBuffer: TensorBuffer,
    ): Bitmap =
        withContext(Dispatchers.Default) {
            val fgColorsBuffer = fgColorsTensorBuffer.buffer
            val maskBuffer = maskTensorBuffer.buffer
            val height = fgColorsTensorBuffer.shape[1]
            val width = fgColorsTensorBuffer.shape[2]

            fgColorsBuffer.rewind()
            maskBuffer.rewind()

            val pixels = IntArray(maskBuffer.remaining())
            var i = 0
            while (fgColorsBuffer.hasRemaining()) {
                pixels[i] =
                    Color.argb(
                        maskBuffer.float,
                        fgColorsBuffer.float,
                        fgColorsBuffer.float,
                        fgColorsBuffer.float,
                    )
                i++
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        }

    suspend fun getConfidentOriginalBitmapPixels(mask: Bitmap, original: Bitmap): Bitmap =
        withContext(Dispatchers.Default) {
            val canvas = Canvas(mask)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                }
            canvas.drawBitmap(original, 0f, 0f, paint)
            mask
        }

    suspend fun drawConfidentFGOverMaskedFGColors(
        maskedFGColors: Bitmap,
        confidentFG: Bitmap,
    ): Bitmap =
        withContext(Dispatchers.Default) {
            val canvas = Canvas(maskedFGColors)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
                }
            canvas.drawBitmap(confidentFG, 0f, 0f, paint)
            maskedFGColors
        }
}
