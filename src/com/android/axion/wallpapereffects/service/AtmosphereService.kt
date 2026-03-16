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

package com.android.axion.wallpapereffects.service

import android.animation.ValueAnimator
import android.app.WallpaperColors
import android.app.wallpaper.WallpaperDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.animation.LinearInterpolator
import com.android.axion.wallpapereffects.renderer.AtmosphereRenderer
import com.google.android.torus.core.wallpaper.LiveWallpaper
import com.google.android.torus.core.wallpaper.listener.LiveWallpaperEventListener
import com.google.android.torus.core.wallpaper.listener.LiveWallpaperKeyguardEventListener
import java.io.File

private const val ANIM_DURATION_MS = 2500L
private const val ACTION_RELOAD = "com.android.axion.wallpapereffects.RELOAD_WALLPAPER"

class AtmosphereService : LiveWallpaper() {

    override fun getWallpaperEngine(
        context: Context,
        surfaceHolder: SurfaceHolder,
        wallpaperDescription: WallpaperDescription?,
    ) = AtmosphereEngine(context, surfaceHolder)
}

class AtmosphereEngine(context: Context, surfaceHolder: SurfaceHolder) :
    GLTorusEngine(context, surfaceHolder),
    LiveWallpaperEventListener,
    LiveWallpaperKeyguardEventListener {

    private var myRenderer: AtmosphereRenderer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var blurAnimator: ValueAnimator? = null
    private var isLocked = true

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_RELOAD) {
                    myRenderer?.reloadTexture()
                    requestRender()
                }
            }
        }

    override fun create(isFirstActiveInstance: Boolean) {
        myRenderer = AtmosphereRenderer(context)
        initGL(myRenderer!!)

        if (isPreview()) {

            isLocked = false
            myRenderer?.blurStrength = 0f
            handler.postDelayed({ playUnlockAnimation() }, 500)
        } else {
            isLocked = true

            val filter = IntentFilter(ACTION_RELOAD)
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        }

        notifyWallpaperColorsChanged()
    }

    override fun resume() {
        super.resume()
        if (isPreview()) {

            myRenderer?.blurStrength = 0f
            requestRender()
            handler.postDelayed({ playUnlockAnimation() }, 500)
        } else if (isLocked) {
            myRenderer?.blurStrength = 0f
            requestRender()
        } else {
            snapToHomeState()
        }
    }

    override fun destroy(isLastActiveInstance: Boolean) {
        handler.removeCallbacksAndMessages(null)
        blurAnimator?.cancel()
        if (!isPreview()) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
        myRenderer?.cleanup()
        super.destroy(isLastActiveInstance)
    }

    override fun onKeyguardGoingAway() {
        if (isLocked) {
            isLocked = false
            playUnlockAnimation()
        }
    }

    override fun onKeyguardAppearing() {
        blurAnimator?.cancel()
        isLocked = true
        myRenderer?.blurStrength = 0f
        requestRender()
    }

    override fun onOffsetChanged(xOffset: Float, xOffsetStep: Float) {}

    override fun onZoomChanged(zoomLevel: Float) {}

    override fun onWallpaperReapplied() {
        myRenderer?.reloadTexture()
        requestRender()
    }

    override fun computeWallpaperColors(): WallpaperColors? {
        return try {
            val deCtx = context.createDeviceProtectedStorageContext()
            val file = File(deCtx.filesDir, "wallpaper.jpg")
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
                val colors = bitmap?.let { WallpaperColors.fromBitmap(it) }
                bitmap?.recycle()
                colors
            } else null
        } catch (_: Exception) {
            null
        }
    }

    override fun onWake(extras: Bundle) {}

    override fun onSleep(extras: Bundle) {
        blurAnimator?.cancel()
    }

    private fun playUnlockAnimation() {
        val r = myRenderer ?: return
        blurAnimator?.cancel()
        r.blurStrength = 0f
        requestRender()

        blurAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ANIM_DURATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    r.blurStrength = animation.animatedValue as Float
                    requestRender()
                }
                start()
            }
    }

    private fun snapToHomeState() {
        val r = myRenderer ?: return
        blurAnimator?.cancel()
        r.blurStrength = 1f
        requestRender()
    }
}
