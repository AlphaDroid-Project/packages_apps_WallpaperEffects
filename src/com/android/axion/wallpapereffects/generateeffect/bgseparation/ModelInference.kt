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
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

abstract class ModelInference(context: Context) {

    val interpreter: Interpreter
    val outputTensorBuffers = HashMap<Int, TensorBuffer>(2)

    init {
        val modelFile = getModelFile()
        try {
            val modelsDir = File(context.cacheDir.absolutePath, "models")
            modelsDir.mkdirs()
            val cachedModel = File(modelsDir.absolutePath, modelFile)
            if (!cachedModel.exists()) {
                context.assets.open(modelFile).use { input ->
                    val bytes = input.readBytes()
                    FileOutputStream(cachedModel).use { output -> output.write(bytes) }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to copy ML model files: $modelFile")
        }

        val options = Interpreter.Options().apply { setNumThreads(NUM_THREADS) }
        val modelPath = File(context.cacheDir, "models/$modelFile").absolutePath
        interpreter = Interpreter(File(modelPath), options)
    }

    abstract fun getModelFile(): String

    suspend fun run(inputBuffers: Array<ByteBuffer>) =
        withContext(Dispatchers.Default) {
            val outputCount = interpreter.outputTensorCount
            outputTensorBuffers.clear()
            val outputs = HashMap<Int, Any>(outputCount)
            for (i in 0 until outputCount) {
                val tensorBuffer =
                    TensorBuffer.createFixedSize(
                        interpreter.getOutputTensor(i).shape(),
                        DataType.FLOAT32,
                    )
                outputTensorBuffers[i] = tensorBuffer
                outputs[i] = tensorBuffer.buffer
            }
            interpreter.runForMultipleInputsOutputs(inputBuffers as Array<Any>, outputs)
        }

    fun close() {
        interpreter.close()
    }

    companion object {
        private const val TAG = "ModelInference"
        private const val NUM_THREADS = 4
    }
}
