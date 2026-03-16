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

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.SurfaceHolder
import com.google.android.torus.core.engine.TorusEngine
import com.google.android.torus.core.wallpaper.LiveWallpaper

abstract class GLTorusEngine(
    protected val context: Context,
    private val surfaceHolder: SurfaceHolder,
) : LiveWallpaper.LiveWallpaperConnector(), TorusEngine {

    private var glSurfaceView: WallpaperGLSurfaceView? = null

    protected fun initGL(renderer: GLSurfaceView.Renderer) {
        glSurfaceView =
            WallpaperGLSurfaceView.create(context, surfaceHolder).also {
                it.setRenderer(renderer)
                it.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
    }

    protected fun requestRender() {
        glSurfaceView?.requestRender()
    }

    override fun resume() {
        glSurfaceView?.onResume()
    }

    override fun pause() {
        glSurfaceView?.onPause()
    }

    override fun resize(width: Int, height: Int) {}

    override fun destroy(isLastActiveInstance: Boolean) {
        glSurfaceView?.onPause()
        glSurfaceView = null
    }

    private class WallpaperGLSurfaceView private constructor(context: Context) :
        GLSurfaceView(context) {

        companion object {

            private var pendingHolder: SurfaceHolder? = null

            fun create(context: Context, holder: SurfaceHolder): WallpaperGLSurfaceView {
                pendingHolder = holder
                return WallpaperGLSurfaceView(context).also { pendingHolder = null }
            }
        }

        private var holder: SurfaceHolder? = null

        init {

            holder = pendingHolder
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
        }

        override fun getHolder(): SurfaceHolder = holder ?: pendingHolder!!
    }
}
