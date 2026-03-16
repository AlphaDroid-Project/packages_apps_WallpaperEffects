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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp

class RaidSegmentationInference(context: Context) : ModelInference(context) {

    override fun getModelFile(): String = "mobile_bg_removal_mosaic_dm1_w_metadata.f16.tflite"

    suspend fun convertBitmapToInputTensorImage(bitmap: Bitmap): TensorImage =
        withContext(Dispatchers.Default) {
            val shape = interpreter.getInputTensor(0).shape()
            val processor =
                ImageProcessor.Builder()
                    .add(ResizeOp(shape[1], shape[2], ResizeOp.ResizeMethod.BILINEAR))
                    .add(CastOp(DataType.FLOAT32))
                    .build()
            val tensorImage = TensorImage(DataType.UINT8)
            tensorImage.load(bitmap)
            processor.process(tensorImage)
        }

    suspend fun convertMaskOutputTensorToBitmap(): Bitmap =
        withContext(Dispatchers.Default) {
            val shape = interpreter.getOutputTensor(1).shape()
            val tensorBuffer = outputTensorBuffers[1]!!

            val processor =
                TensorProcessor.Builder()
                    .add(NormalizeOp(0f, 0.003921569f))
                    .add(CastOp(DataType.UINT8))
                    .build()
            val processed = processor.process(tensorBuffer)

            val height = shape[1]
            val width = shape[2]
            processed.buffer.rewind()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            bitmap.copyPixelsFromBuffer(processed.buffer)
            bitmap
        }
}
