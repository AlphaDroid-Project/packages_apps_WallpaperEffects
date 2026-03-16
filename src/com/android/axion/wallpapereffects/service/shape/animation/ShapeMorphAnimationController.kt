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

package com.android.axion.wallpapereffects.service.shape.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.Path
import android.util.Log
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.transformed
import com.android.axion.wallpapereffects.service.shape.RotationDirection
import com.android.axion.wallpapereffects.service.shape.Shape
import com.android.axion.wallpapereffects.service.shape.ShapeEffectConstants
import com.android.axion.wallpapereffects.service.shape.ShapeEffectState
import com.android.axion.wallpapereffects.service.shape.ShapeRenderModel

class ShapeMorphAnimationController(private val callback: ShapeRendererCallback) :
    ShapeEffectAnimationController {

    private val animator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ShapeEffectConstants.SHAPE_ANIMATION_DURATION_MS
            interpolator = ShapeEffectConstants.SHAPE_MORPH_ANIMATION_INTERPOLATOR
        }
    private var currentShape: Shape = Shape.NONE

    override fun isRunning(): Boolean = animator.isRunning

    override fun onShapeEffectStateChanged(state: ShapeEffectState) {
        val (shape, rotationDir) =
            when (state) {
                is ShapeEffectState.WithShape -> Pair(state.shape, state.rotationDirection)
                is ShapeEffectState.WithoutShape -> Pair(Shape.NONE, RotationDirection.NONE)
            }

        val prevShape = currentShape
        currentShape = shape

        if (shape == Shape.NONE) return

        if (prevShape == Shape.NONE) {
            updatePath(shape.path)
            return
        }

        if (prevShape == shape) return

        val morphPath = Path()
        animator.removeAllListeners()
        animator.removeAllUpdateListeners()
        animator.cancel()

        val rotationAngle =
            when (rotationDir) {
                RotationDirection.CLOCKWISE -> 90f
                RotationDirection.COUNTERCLOCKWISE -> -90f
                RotationDirection.NONE -> {
                    Log.w(
                        TAG,
                        "Attempted a RotationDirection.NONE transition: $prevShape -> $shape",
                    )
                    updatePath(shape.path)
                    return
                }
            }

        val startPolygon = prevShape.roundedPolygon
        val counterRotMatrix = Matrix().apply { postRotate(-rotationAngle) }
        val targetPolygonRotated = shape.roundedPolygon.transformed(counterRotMatrix)
        val morph = Morph(startPolygon, targetPolygonRotated)

        animator.addUpdateListener { anim ->
            val fraction = anim.animatedValue as Float
            morph.toPath(fraction, morphPath)
            val rotMatrix = Matrix().apply { postRotate(rotationAngle * fraction) }
            morphPath.transform(rotMatrix)
            updatePath(morphPath)
        }

        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    updatePath(shape.path)
                    callback.onAnimationStop()
                }

                override fun onAnimationPause(animation: Animator) {
                    callback.onAnimationStop()
                }

                override fun onAnimationResume(animation: Animator) {
                    callback.onAnimationStart()
                }
            }
        )

        callback.onAnimationStart()
        animator.start()
    }

    override fun onTap() {}

    override fun reset() {
        animator.cancel()
        animator.removeAllListeners()
        animator.removeAllUpdateListeners()
        currentShape = Shape.NONE
    }

    private fun updatePath(path: Path) {
        callback.onShapeRenderModelUpdated { model ->
            if (model is ShapeRenderModel.WithShape) {
                model.copy(shapePath = path)
            } else model
        }
    }

    companion object {
        private const val TAG = "ShapeMorphAnimCtrl"
    }
}
