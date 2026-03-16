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

package com.android.axion.wallpapereffects.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.android.axion.wallpapereffects.R
import com.android.axion.wallpapereffects.util.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

private const val TAG = "AtmosphereRenderer"
private const val MAX_BLOBS = 16
private const val BLUR_RADIUS = 200f

class AtmosphereRenderer(private val context: Context) : GLSurfaceView.Renderer {

    var blurStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    var dimLevel: Float = 0.2f
    @Volatile var needsReload: Boolean = false
    var enableNoise: Boolean = false
    var noiseScale: Float = 2000f
    var noiseStrength: Float = 0.06f

    private var programId = 0
    private var blurProgramId = 0
    private var fboId = 0
    private var tempTextureId = 0
    private var aspectRatio = 1f

    private var sharpTextureId = 0
    private var blurTextureId = 0

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val blobs = mutableListOf<BlobPhysics>()
    private val random = Random()

    private val blobColorsBuffer = FloatArray(MAX_BLOBS * 3)
    private val blobPosBuffer = FloatArray(MAX_BLOBS * 2)
    private val blobSizesBuffer = FloatArray(MAX_BLOBS)

    private val vertices =
        floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
    private lateinit var vertexBuffer: FloatBuffer

    fun reloadTexture() {
        needsReload = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vertexBuffer =
            ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertices)
        vertexBuffer.position(0)

        val vertexCode = loadRawResource(context, R.raw.passthrough_vert)
        val fragmentCode = loadRawResource(context, R.raw.atmosphere_frag)
        programId = createProgram(vertexCode, fragmentCode)
        Log.d(TAG, "Main program: $programId")

        val blurFragCode =
            """
            #version 300 es
            precision highp float;
            in vec2 vTexCoord;
            out vec4 fragColor;
            uniform sampler2D uTexture;
            uniform vec2 uDirection;
            uniform float uRadius;
            void main() {
                vec2 texelSize = 1.0 / vec2(textureSize(uTexture, 0));
                vec3 result = vec3(0.0);
                float totalWeight = 0.0;
                for(float i = -uRadius; i <= uRadius; i++) {
                    vec2 offset = uDirection * i * texelSize;
                    float weight = 1.0 - abs(i) / uRadius;
                    result += texture(uTexture, vTexCoord + offset).rgb * weight;
                    totalWeight += weight;
                }
                fragColor = vec4(result / totalWeight, 1.0);
            }
            """
                .trimIndent()
        blurProgramId = createProgram(vertexCode, blurFragCode)
        Log.d(TAG, "Blur program: $blurProgramId")

        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        fboId = fbo[0]

        loadAndApplyTextures()
    }

    private fun loadAndApplyTextures() {

        if (sharpTextureId != 0) {
            GLES30.glDeleteTextures(2, intArrayOf(sharpTextureId, blurTextureId), 0)
            sharpTextureId = 0
            blurTextureId = 0
        }
        if (tempTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(tempTextureId), 0)
            tempTextureId = 0
        }

        val bitmap = loadFixedWallpaper()
        Log.d(TAG, "Loaded bitmap: ${bitmap.width}x${bitmap.height}")

        sharpTextureId = uploadTexture(bitmap)
        tempTextureId = createEmptyTexture(bitmap.width, bitmap.height)
        blurTextureId = gpuBlur(sharpTextureId, bitmap.width, bitmap.height)

        val blurredBitmap = downloadTexture(blurTextureId, bitmap.width, bitmap.height)
        initBaseBlobs(blurredBitmap)

        bitmap.recycle()
        blurredBitmap.recycle()

        Log.d(TAG, "Textures ready: sharp=$sharpTextureId blur=$blurTextureId, ${blobs.size} blobs")
    }

    private fun loadFixedWallpaper(): Bitmap {

        try {
            val deCtx = context.createDeviceProtectedStorageContext()
            val file = File(deCtx.filesDir, "wallpaper.jpg")
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    Log.d(TAG, "Wallpaper loaded from DE storage: ${bitmap.width}x${bitmap.height}")
                    return bitmap
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from DE storage", e)
        }

        try {
            val wm = android.app.WallpaperManager.getInstance(context)
            val drawable = wm.getDrawable()
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                Log.d(
                    TAG,
                    "Wallpaper loaded from WallpaperManager: " +
                        "${drawable.bitmap.width}x${drawable.bitmap.height}",
                )
                return drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from WallpaperManager", e)
        }
        Log.w(TAG, "No wallpaper available, using fallback")
        val fallback = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        fallback.eraseColor(Color.BLUE)
        return fallback
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
        aspectRatio = width.toFloat() / height.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        if (needsReload) {
            needsReload = false
            loadAndApplyTextures()

            if (surfaceWidth > 0 && surfaceHeight > 0) {
                GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
            }
        }

        if (sharpTextureId == 0 || blurTextureId == 0) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(programId)

        val t = blurStrength.coerceIn(0f, 1f)
        val physicsRaw = (t - 0.1f) / 0.9f
        val physicsT = physicsRaw.coerceIn(0f, 1f)
        val progress = 1f - (1f - physicsT).pow(3)

        var idx = 0
        for (b in blobs) {
            if (idx >= MAX_BLOBS) break
            val u = 1f - progress
            val tt = progress * progress
            val uu = u * u
            val ut2 = 2 * u * progress
            val bx = (uu * b.startX) + (ut2 * b.p1x) + (tt * b.endX)
            val by = (uu * b.startY) + (ut2 * b.p1y) + (tt * b.endY)
            val bSize = b.startSize + (b.endSize - b.startSize) * progress

            blobPosBuffer[idx * 2] = bx
            blobPosBuffer[idx * 2 + 1] = by
            blobSizesBuffer[idx] = bSize
            blobColorsBuffer[idx * 3] = b.color[0]
            blobColorsBuffer[idx * 3 + 1] = b.color[1]
            blobColorsBuffer[idx * 3 + 2] = b.color[2]
            idx++
        }

        if (idx > 0) {
            GLES30.glUniform3fv(
                GLES30.glGetUniformLocation(programId, "uBlobColors"),
                idx,
                blobColorsBuffer,
                0,
            )
            GLES30.glUniform2fv(
                GLES30.glGetUniformLocation(programId, "uBlobPositions"),
                idx,
                blobPosBuffer,
                0,
            )
            GLES30.glUniform1fv(
                GLES30.glGetUniformLocation(programId, "uBlobSizes"),
                idx,
                blobSizesBuffer,
                0,
            )
        }

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBlobCount"), idx)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAspectRatio"), aspectRatio)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBlurStrength"), blurStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDimLevel"), dimLevel)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uEnableNoise"),
            if (enableNoise) 1f else 0f,
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNoiseScale"), noiseScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNoiseStrength"), noiseStrength)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sharpTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureSharp"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureBlur"), 1)

        val aPosLoc = GLES30.glGetAttribLocation(programId, "aPosition")
        val aTexLoc = GLES30.glGetAttribLocation(programId, "aTexCoord")
        drawQuad(aPosLoc, aTexLoc)
    }

    private fun initBaseBlobs(blurred: Bitmap) {
        val rawClusters = extractColorsFromBlurred(blurred, 16)
        blobs.clear()

        data class TempCluster(
            var r: Int,
            var g: Int,
            var b: Int,
            var x: Float,
            var y: Float,
            var count: Int,
        )

        val tempClusters =
            rawClusters
                .map {
                    TempCluster(
                        Color.red(it.color),
                        Color.green(it.color),
                        Color.blue(it.color),
                        it.centerX,
                        it.centerY,
                        1,
                    )
                }
                .toMutableList()

        val mergedClusters = mutableListOf<TempCluster>()
        val processed = BooleanArray(tempClusters.size)

        for (i in tempClusters.indices) {
            if (processed[i]) continue
            val main = tempClusters[i]
            processed[i] = true

            for (j in i + 1 until tempClusters.size) {
                if (processed[j]) continue
                val other = tempClusters[j]

                val colorDist =
                    hypot((main.r - other.r).toFloat(), (main.g - other.g).toFloat()) +
                        abs(main.b - other.b)
                val spatialDist = hypot(main.x - other.x, main.y - other.y)

                if (colorDist < 90f && spatialDist < 0.25f) {
                    val totalCount = main.count + other.count
                    main.x = (main.x * main.count + other.x * other.count) / totalCount
                    main.y = (main.y * main.count + other.y * other.count) / totalCount
                    main.r = (main.r * main.count + other.r * other.count) / totalCount
                    main.g = (main.g * main.count + other.g * other.count) / totalCount
                    main.b = (main.b * main.count + other.b * other.count) / totalCount
                    main.count += other.count
                    processed[j] = true
                }
            }
            mergedClusters.add(main)
        }

        for (cluster in mergedClusters) {
            val clr = floatArrayOf(cluster.r / 255f, cluster.g / 255f, cluster.b / 255f)
            val massScale = min(1.4f, 1f + (cluster.count * 0.05f))

            blobs.add(
                BlobPhysics(
                    color = clr,
                    startX = cluster.x,
                    startY = cluster.y,
                    p1x = 0f,
                    p1y = 0f,
                    endX = 0f,
                    endY = 0f,
                    startSize = 0f,
                    endSize = 0f,
                    massScale = massScale,
                )
            )
        }

        reRollTargets()
    }

    fun reRollTargets() {
        for (blob in blobs) {
            blob.endX = 0.05f + random.nextFloat() * 0.9f
            blob.endY = 0.05f + random.nextFloat() * 0.9f

            val midX = (blob.startX + blob.endX) / 2f
            val midY = (blob.startY + blob.endY) / 2f
            blob.p1x = midX + (random.nextFloat() - 0.5f) * 0.5f
            blob.p1y = midY + (random.nextFloat() - 0.5f) * 0.5f

            val baseSize = 0.12f + random.nextFloat() * 0.08f
            blob.startSize = 0.05f
            blob.endSize = baseSize * blob.massScale
        }
    }

    private fun gpuBlur(inputTexture: Int, width: Int, height: Int): Int {
        val outputTexture = createEmptyTexture(width, height)
        GLES30.glUseProgram(blurProgramId)
        val aPosLoc = GLES30.glGetAttribLocation(blurProgramId, "aPosition")
        val aTexLoc = GLES30.glGetAttribLocation(blurProgramId, "aTexCoord")

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            tempTextureId,
            0,
        )
        GLES30.glViewport(0, 0, width, height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blurProgramId, "uTexture"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blurProgramId, "uDirection"), 1f, 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(blurProgramId, "uRadius"), BLUR_RADIUS)
        drawQuad(aPosLoc, aTexLoc)

        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            outputTexture,
            0,
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tempTextureId)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blurProgramId, "uDirection"), 0f, 1f)
        drawQuad(aPosLoc, aTexLoc)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return outputTexture
    }

    private fun downloadTexture(textureId: Int, width: Int, height: Int): Bitmap {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        buffer.order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textureId,
            0,
        )
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun uploadTexture(bitmap: Bitmap): Int {
        val textureHandle = IntArray(1)
        GLES30.glGenTextures(1, textureHandle, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureHandle[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        return textureHandle[0]
    }

    private fun createEmptyTexture(width: Int, height: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        return t[0]
    }

    private fun drawQuad(aPosLoc: Int, aTexLoc: Int) {
        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(aPosLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aPosLoc)
        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(aTexLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aTexLoc)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosLoc)
        GLES30.glDisableVertexAttribArray(aTexLoc)
    }

    private data class ColorCluster(val color: Int, val centerX: Float, val centerY: Float)

    private data class ColorPoint(val color: Int, val x: Int, val y: Int)

    private fun extractColorsFromBlurred(
        blurred: Bitmap,
        targetColors: Int = 16,
    ): List<ColorCluster> {
        val w = blurred.width
        val h = blurred.height
        val samples = mutableListOf<ColorPoint>()
        val step = 10
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                samples.add(ColorPoint(blurred.getPixel(x, y), x, y))
            }
        }
        val colorBuckets = medianCut(samples, targetColors)
        val colorClusters = mutableListOf<ColorCluster>()
        for (bucket in colorBuckets) {
            if (bucket.isEmpty()) continue
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            var sumX = 0f
            var sumY = 0f
            for (point in bucket) {
                sumR += Color.red(point.color)
                sumG += Color.green(point.color)
                sumB += Color.blue(point.color)
                sumX += point.x
                sumY += point.y
            }
            val count = bucket.size
            val avgColor =
                Color.rgb((sumR / count).toInt(), (sumG / count).toInt(), (sumB / count).toInt())
            colorClusters.add(ColorCluster(avgColor, sumX / count / w, sumY / count / h))
        }
        return colorClusters
    }

    private fun medianCut(pixels: List<ColorPoint>, targetBuckets: Int): List<List<ColorPoint>> {
        val buckets = mutableListOf<MutableList<ColorPoint>>()
        buckets.add(pixels.toMutableList())
        while (buckets.size < targetBuckets) {
            var largestBucket: MutableList<ColorPoint>? = null
            var largestRange = 0
            var splitChannel = 0
            for (bucket in buckets) {
                if (bucket.size <= 1) continue
                val reds = bucket.map { Color.red(it.color) }
                val greens = bucket.map { Color.green(it.color) }
                val blues = bucket.map { Color.blue(it.color) }
                val rRange = (reds.maxOrNull() ?: 0) - (reds.minOrNull() ?: 0)
                val gRange = (greens.maxOrNull() ?: 0) - (greens.minOrNull() ?: 0)
                val bRange = (blues.maxOrNull() ?: 0) - (blues.minOrNull() ?: 0)
                val maxRange = maxOf(rRange, gRange, bRange)
                if (maxRange > largestRange) {
                    largestRange = maxRange
                    largestBucket = bucket
                    splitChannel = if (maxRange == rRange) 0 else if (maxRange == gRange) 1 else 2
                }
            }
            if (largestBucket == null) break
            val sorted =
                when (splitChannel) {
                    0 -> largestBucket.sortedBy { Color.red(it.color) }
                    1 -> largestBucket.sortedBy { Color.green(it.color) }
                    else -> largestBucket.sortedBy { Color.blue(it.color) }
                }
            val median = sorted.size / 2
            buckets.remove(largestBucket)
            buckets.add(sorted.subList(0, median).toMutableList())
            buckets.add(sorted.subList(median, sorted.size).toMutableList())
        }
        return buckets
    }

    fun cleanup() {
        if (sharpTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(sharpTextureId), 0)
        if (blurTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(blurTextureId), 0)
        if (tempTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(tempTextureId), 0)
        sharpTextureId = 0
        blurTextureId = 0
        tempTextureId = 0
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
        if (blurProgramId != 0) {
            GLES30.glDeleteProgram(blurProgramId)
            blurProgramId = 0
        }
        if (fboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
    }
}

data class BlobPhysics(
    val color: FloatArray,
    val startX: Float,
    val startY: Float,
    var p1x: Float,
    var p1y: Float,
    var endX: Float,
    var endY: Float,
    var startSize: Float,
    var endSize: Float,
    val massScale: Float,
)
