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

import android.app.WallpaperColors
import android.app.wallpaper.WallpaperDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.SurfaceHolder
import com.android.axion.wallpapereffects.renderer.GlassRenderer
import com.google.android.torus.core.wallpaper.LiveWallpaper
import com.google.android.torus.core.wallpaper.listener.LiveWallpaperEventListener
import java.io.File

private const val ACTION_RELOAD = "com.android.axion.wallpapereffects.RELOAD_WALLPAPER"

class GlassService : LiveWallpaper() {

    override fun getWallpaperEngine(
        context: Context,
        surfaceHolder: SurfaceHolder,
        wallpaperDescription: WallpaperDescription?,
    ) = GlassEngine(context, surfaceHolder)
}

class GlassEngine(context: Context, surfaceHolder: SurfaceHolder) :
    GLTorusEngine(context, surfaceHolder), LiveWallpaperEventListener {

    private var myRenderer: GlassRenderer? = null

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
        myRenderer = GlassRenderer(context)
        initGL(myRenderer!!)

        val filter = IntentFilter(ACTION_RELOAD)
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)

        notifyWallpaperColorsChanged()
    }

    override fun resume() {
        super.resume()
        requestRender()
    }

    override fun destroy(isLastActiveInstance: Boolean) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {}
        myRenderer?.cleanup()
        super.destroy(isLastActiveInstance)
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

    override fun onSleep(extras: Bundle) {}
}
