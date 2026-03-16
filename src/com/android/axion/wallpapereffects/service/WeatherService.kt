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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.util.SizeF
import android.view.SurfaceHolder
import com.android.axion.quicklook.IAxQuickLookService
import com.android.axion.quicklook.IQuickLookCallback
import com.android.axion.quicklook.QuickLookTarget
import com.android.axion.quicklook.weatherData
import com.android.wallpaper.weathereffects.graphics.WeatherEffect
import com.android.wallpaper.weathereffects.graphics.clouds.CloudsEffect
import com.android.wallpaper.weathereffects.graphics.clouds.CloudsEffectConfig
import com.android.wallpaper.weathereffects.graphics.fog.FogEffect
import com.android.wallpaper.weathereffects.graphics.fog.FogEffectConfig
import com.android.wallpaper.weathereffects.graphics.rain.RainEffect
import com.android.wallpaper.weathereffects.graphics.rain.RainEffectConfig
import com.android.wallpaper.weathereffects.graphics.snow.SnowEffect
import com.android.wallpaper.weathereffects.graphics.snow.SnowEffectConfig
import com.android.wallpaper.weathereffects.graphics.sun.SunEffect
import com.android.wallpaper.weathereffects.graphics.sun.SunEffectConfig
import com.google.android.torus.canvas.engine.CanvasWallpaperEngine
import com.google.android.torus.core.power.FpsThrottler
import com.google.android.torus.core.wallpaper.LiveWallpaper
import com.google.android.torus.core.wallpaper.listener.LiveWallpaperEventListener
import java.io.File

private const val TAG = "WeatherService"
private const val ACTION_RELOAD = "com.android.axion.wallpapereffects.RELOAD_WALLPAPER"
private const val QUICKLOOK_SERVICE = "com.android.axion.quicklook.SERVICE"
private const val QUICKLOOK_PKG = "com.android.axion.quicklook"

private const val SETTING_WEATHER_TYPE = "ax_effect_weather_type"
private const val SETTING_WEATHER_INTENSITY = "ax_effect_weather_intensity"

class WeatherService : LiveWallpaper() {

    override fun getWallpaperEngine(
        context: Context,
        surfaceHolder: SurfaceHolder,
        wallpaperDescription: WallpaperDescription?,
    ): WeatherEngine = WeatherEngine(context, surfaceHolder, wallpaperDescription)
}

class WeatherEngine(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    private val wallpaperDescription: WallpaperDescription?,
) : CanvasWallpaperEngine(surfaceHolder, hardwareAccelerated = true), LiveWallpaperEventListener {

    private var weatherEffect: WeatherEffect? = null
    private var currentWeatherType: String = "sun"
    private var settingsWeatherType: String = "auto"
    private var settingsIntensity: Float = 0.7f
    private var wallpaperBitmap: Bitmap? = null
    private var quickLookService: IAxQuickLookService? = null
    private var serviceBound = false

    private val quickLookCallback =
        object : IQuickLookCallback.Stub() {
            override fun onTargetsUpdated(targets: MutableList<QuickLookTarget>) {
                if (settingsWeatherType != "auto") return

                val weather =
                    targets
                        .firstOrNull { it.targetType == QuickLookTarget.TYPE_WEATHER }
                        ?.weatherData
                if (weather != null) {
                    val newType = mapConditionToType(weather.conditionCode, weather.condition)
                    if (newType != currentWeatherType) {
                        currentWeatherType = newType
                        recreateEffect()
                    }
                }
            }
        }

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                quickLookService = IAxQuickLookService.Stub.asInterface(service)
                try {
                    quickLookService?.registerCallback(quickLookCallback)
                    if (settingsWeatherType == "auto") {
                        val targets = quickLookService?.currentTargets
                        val weather =
                            targets
                                ?.firstOrNull { it.targetType == QuickLookTarget.TYPE_WEATHER }
                                ?.weatherData
                        if (weather != null) {
                            currentWeatherType =
                                mapConditionToType(weather.conditionCode, weather.condition)
                            recreateEffect()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get weather data", e)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                quickLookService = null
            }
        }

    private val reloadReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_RELOAD) {
                    readSettings()
                    if (settingsWeatherType != "auto") {
                        currentWeatherType = settingsWeatherType
                    }
                    loadWallpaper()
                    recreateEffect()
                }
            }
        }

    override fun onCreate(isFirstActiveInstance: Boolean) {
        readSettings()
        if (settingsWeatherType != "auto") {
            currentWeatherType = settingsWeatherType
        }
        loadWallpaper()

        val filter = IntentFilter(ACTION_RELOAD)
        context.registerReceiver(reloadReceiver, filter, Context.RECEIVER_EXPORTED)

        bindQuickLook()

        notifyWallpaperColorsChanged()

        setFpsLimit(FpsThrottler.FPS_30)
    }

    override fun onResume() {
        if (weatherEffect == null) recreateEffect()
        startUpdateLoop()
    }

    override fun onPause() {
        stopUpdateLoop()
    }

    override fun onResize(size: Size) {
        weatherEffect?.resize(SizeF(size.width.toFloat(), size.height.toFloat()))
    }

    override fun onUpdate(deltaMillis: Long, frameTimeNanos: Long) {
        val effect = weatherEffect ?: return
        renderWithFpsLimit(frameTimeNanos) { canvas ->
            effect.update(deltaMillis, frameTimeNanos)
            effect.draw(canvas)
        }
    }

    override fun onDestroy(isLastActiveInstance: Boolean) {
        stopUpdateLoop()
        try {
            context.unregisterReceiver(reloadReceiver)
        } catch (_: Exception) {}
        try {
            quickLookService?.unregisterCallback(quickLookCallback)
        } catch (_: Exception) {}
        if (serviceBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {}
            serviceBound = false
        }
        weatherEffect?.release()
        weatherEffect = null
        wallpaperBitmap?.recycle()
        wallpaperBitmap = null
    }

    override fun onOffsetChanged(xOffset: Float, xOffsetStep: Float) {}

    override fun onZoomChanged(zoomLevel: Float) {}

    override fun onWallpaperReapplied() {
        readSettings()
        if (settingsWeatherType != "auto") {
            currentWeatherType = settingsWeatherType
        }
        loadWallpaper()
        recreateEffect()
    }

    override fun computeWallpaperColors(): WallpaperColors? {
        val wp = wallpaperBitmap ?: return null
        return WallpaperColors.fromBitmap(wp)
    }

    override fun onWake(extras: Bundle) {}

    override fun onSleep(extras: Bundle) {}

    private fun readSettings() {
        try {
            val content = wallpaperDescription?.content
            if (content != null && content.containsKey("weather_type")) {
                settingsWeatherType = content.getString("weather_type") ?: "auto"
                settingsIntensity = content.getDouble("weather_intensity", 0.7).toFloat()
            } else {
                val cr = context.contentResolver
                settingsWeatherType = Settings.Secure.getString(cr, SETTING_WEATHER_TYPE) ?: "auto"
                val intensityStr = Settings.Secure.getString(cr, SETTING_WEATHER_INTENSITY)
                settingsIntensity = intensityStr?.toFloatOrNull() ?: 0.7f
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read settings", e)
        }
    }

    private fun loadWallpaper() {
        wallpaperBitmap?.recycle()
        wallpaperBitmap =
            loadWallpaperFromSystem()
                ?: Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.DKGRAY)
                }
    }

    private fun loadWallpaperFromSystem(): Bitmap? {
        try {
            val deCtx = context.createDeviceProtectedStorageContext()
            val file = File(deCtx.filesDir, "wallpaper.jpg")
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    Log.d(
                        TAG,
                        "Loaded wallpaper from DE storage: " + "${bitmap.width}x${bitmap.height}",
                    )
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
                    "Loaded wallpaper from WallpaperManager: " +
                        "${drawable.bitmap.width}x${drawable.bitmap.height}",
                )
                return drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from WallpaperManager", e)
        }
        return null
    }

    private fun recreateEffect() {
        weatherEffect?.release()
        weatherEffect = null

        val bmp = wallpaperBitmap ?: return
        val size = screenSize
        if (size.width <= 0 || size.height <= 0) return

        val surfaceSize = SizeF(size.width.toFloat(), size.height.toFloat())
        val density = context.resources.displayMetrics.density

        try {
            weatherEffect = createWeatherEffect(currentWeatherType, bmp, surfaceSize, density)
            weatherEffect?.setIntensity(settingsIntensity)
            Log.d(TAG, "Created weather effect: $currentWeatherType, intensity: $settingsIntensity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create weather effect: $currentWeatherType", e)
        }
    }

    private fun createWeatherEffect(
        type: String,
        bitmap: Bitmap,
        surfaceSize: SizeF,
        density: Float,
    ): WeatherEffect {
        val assets = context.assets
        val executor = context.mainExecutor
        return when (type) {
            "rain" -> {
                val config = RainEffectConfig(assets, density)
                RainEffect(config, bitmap, bitmap, settingsIntensity, surfaceSize, executor)
            }
            "snow" -> {
                val config = SnowEffectConfig(assets, density)
                SnowEffect(config, bitmap, bitmap, settingsIntensity, surfaceSize, executor)
            }
            "fog" -> {
                val config = FogEffectConfig(assets, density)
                FogEffect(config, bitmap, bitmap, settingsIntensity, surfaceSize)
            }
            "clouds" -> {
                val config = CloudsEffectConfig(assets, density)
                CloudsEffect(config, bitmap, bitmap, settingsIntensity, surfaceSize)
            }
            else -> {
                val config = SunEffectConfig(assets, density)
                SunEffect(config, bitmap, bitmap, settingsIntensity, surfaceSize)
            }
        }
    }

    private fun bindQuickLook() {
        try {
            val intent = Intent(QUICKLOOK_SERVICE).setPackage(QUICKLOOK_PKG)
            serviceBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!serviceBound) {
                Log.w(TAG, "Could not bind to AxQuickLook")
            }
        } catch (e: Exception) {
            Log.w(TAG, "AxQuickLook not available", e)
        }
    }

    private fun mapConditionToType(code: Int, condition: String): String {
        return when {
            code in 200..599 -> "rain"
            code in 600..699 -> "snow"
            code in 700..799 -> "fog"
            code == 800 -> "sun"
            code in 801..899 -> "clouds"
            else -> {
                val lower = condition.lowercase()
                when {
                    lower.contains("rain") ||
                        lower.contains("storm") ||
                        lower.contains("drizzle") ||
                        lower.contains("thunder") -> "rain"
                    lower.contains("snow") ||
                        lower.contains("sleet") ||
                        lower.contains("blizzard") -> "snow"
                    lower.contains("fog") || lower.contains("mist") || lower.contains("haze") ->
                        "fog"
                    lower.contains("cloud") || lower.contains("overcast") -> "clouds"
                    else -> "sun"
                }
            }
        }
    }
}
