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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "GlassRenderer"
private const val BAND_COUNT = 28f
private const val AMPLITUDE = 15f
private const val VERTICAL_RIPPLE = 2f
private const val BRIGHTNESS_FACTOR = 0.05f

class GlassRenderer(private val context: Context) : GLSurfaceView.Renderer {

    @Volatile var needsReload: Boolean = false

    private var programId = 0
    private var textureId = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0

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

        val vertSource = loadRawResource(context, R.raw.passthrough_vert)
        val fragSource = loadRawResource(context, R.raw.glass_frag)
        programId = createProgram(vertSource, fragSource)
        Log.d(TAG, "Program: $programId")

        loadAndApplyTexture()
    }

    private fun loadAndApplyTexture() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }

        val bitmap = loadFixedWallpaper()
        Log.d(TAG, "Loaded bitmap: ${bitmap.width}x${bitmap.height}")

        textureId = uploadTexture(bitmap)
        bitmap.recycle()

        Log.d(TAG, "Texture ready: $textureId")
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

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (needsReload) {
            needsReload = false
            loadAndApplyTexture()
        }

        if (textureId == 0) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(programId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTexture"), 0)

        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(programId, "uResolution"),
            surfaceWidth.toFloat(),
            surfaceHeight.toFloat(),
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBandCount"), BAND_COUNT)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAmplitude"), AMPLITUDE)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uVerticalRipple"),
            VERTICAL_RIPPLE,
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uBrightnessFactor"),
            BRIGHTNESS_FACTOR,
        )

        val aPosLoc = GLES30.glGetAttribLocation(programId, "aPosition")
        val aTexLoc = GLES30.glGetAttribLocation(programId, "aTexCoord")
        drawQuad(aPosLoc, aTexLoc)
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

    fun cleanup() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
    }
}
