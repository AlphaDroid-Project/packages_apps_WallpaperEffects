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

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class TFLiteImageSegmenter(private val context: Context) {

    suspend fun getForegroundImage(image: Bitmap): Bitmap = coroutineScope {
        val startTime = System.currentTimeMillis()

        val raidInference = RaidSegmentationInference(context)
        val deepMattingInference = DeepMattingInference(context)
        val fgEstimationInference = ForegroundEstimationInference(context)

        val segmentationInputDeferred = async {
            raidInference.convertBitmapToInputTensorImage(image)
        }
        val mattingInputDeferred = async {
            deepMattingInference.convertBitmapToInputTensorImage(image)
        }

        val segmentationInput = segmentationInputDeferred.await()
        ensureActive()

        val fgNormalizedInputDeferred = async {
            fgEstimationInference.normalizeInputTensorImage(segmentationInput)
        }

        val raidInputBuffer = segmentationInput.tensorBuffer.buffer
        raidInference.run(arrayOf(raidInputBuffer))
        ensureActive()

        val raidMaskBitmap = raidInference.convertMaskOutputTensorToBitmap()
        val raidDuration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Segmentation complete ${raidDuration}ms")
        raidInference.close()

        val mattingMaskBuffer = deepMattingInference.convertBitmapToInputMaskBuffer(raidMaskBitmap)
        val mattingInput = mattingInputDeferred.await()
        ensureActive()

        val mattingImageBuffer = mattingInput.tensorBuffer.buffer
        deepMattingInference.run(arrayOf(mattingImageBuffer, mattingMaskBuffer))
        ensureActive()

        val mattingBitmaps = deepMattingInference.convertMaskOutputTensorToBitmaps()
        val confidenceBitmap = mattingBitmaps[0]
        val alphaBitmap = mattingBitmaps[1]
        val mattingDuration = System.currentTimeMillis() - startTime - raidDuration
        Log.d(TAG, "Matting complete ${mattingDuration}ms")

        val confidentFGDeferred = async {
            val scaledConfidence =
                Bitmap.createScaledBitmap(confidenceBitmap, image.width, image.height, true)
            SegmentationHelper.getConfidentOriginalBitmapPixels(scaledConfidence, image)
        }

        deepMattingInference.close()

        val fgNormalizedInput = fgNormalizedInputDeferred.await()
        val fgMaskTensorBuffer = fgEstimationInference.convertBitmapToInputMask(alphaBitmap)
        ensureActive()

        val fgImageBuffer = fgNormalizedInput.tensorBuffer.buffer
        val fgMaskByteBuffer = fgMaskTensorBuffer.buffer
        fgEstimationInference.run(arrayOf(fgImageBuffer, fgMaskByteBuffer))
        ensureActive()

        val fgDuration = System.currentTimeMillis() - startTime - raidDuration - mattingDuration
        Log.d(TAG, "FG estimation complete ${fgDuration}ms")

        val fgColorsTensorBuffer = fgEstimationInference.outputTensorBuffers[0]!!
        val maskedFGColors =
            SegmentationHelper.getMaskedFGColors(fgColorsTensorBuffer, fgMaskTensorBuffer)
        fgEstimationInference.close()

        val scaledMaskedFGColors =
            withContext(Dispatchers.Default) {
                Bitmap.createScaledBitmap(maskedFGColors, image.width, image.height, true)
            }

        val confidentFG = confidentFGDeferred.await()
        val result =
            SegmentationHelper.drawConfidentFGOverMaskedFGColors(scaledMaskedFGColors, confidentFG)

        val totalDuration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Total segmentation pipeline: ${totalDuration}ms")

        result
    }

    companion object {
        private const val TAG = "TFLiteImageSegmenter"
    }
}
