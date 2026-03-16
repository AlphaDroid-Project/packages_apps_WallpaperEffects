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
import androidx.core.graphics.ColorUtils
import com.android.axion.wallpapereffects.service.shape.Shape
import com.android.axion.wallpapereffects.service.shape.ShapeEffectConstants
import com.android.axion.wallpapereffects.service.shape.ShapeEffectState
import com.android.axion.wallpapereffects.service.shape.ShapeRenderModel
import com.android.axion.wallpapereffects.util.LstarHelper

class ShapeColorAnimationController(private val callback: ShapeRendererCallback) :
    ShapeEffectAnimationController {

    private val animator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ShapeEffectConstants.COLOR_ANIMATION_DURATION_MS
            interpolator = ShapeEffectConstants.SHAPE_COLOR_ANIMATION_INTERPOLATOR
        }
    private var currentState: ColorState? = null
    private var targetState: ColorState? = null

    data class ColorState(val chipColor: Int, val sliderValue: Float, val shapeColor: Int)

    override fun isRunning(): Boolean = animator.isRunning

    override fun onShapeEffectStateChanged(state: ShapeEffectState) {
        if (state !is ShapeEffectState.WithShape) return
        if (state.shape == Shape.NONE) return

        val chipColor = state.shapeChipColor
        val sliderValue = state.shapeColorSliderValue
        val shapeColor = LstarHelper.colorWithLstar(chipColor, sliderValue)
        val newState = ColorState(chipColor, sliderValue, shapeColor)

        val current = currentState
        if (current == null) {
            updateColor(newState)
            callback.onRedrawNeeded()
            return
        }

        if (current.chipColor == chipColor) {
            if (current.sliderValue == sliderValue) return
            animator.cancel()
            updateColor(newState)
            callback.onRedrawNeeded()
            return
        }

        animator.removeAllListeners()
        animator.removeAllUpdateListeners()
        animator.cancel()

        val startLab = DoubleArray(3)
        ColorUtils.colorToLAB(current.shapeColor, startLab)
        val startL = startLab[0]
        val startA = startLab[1]
        val startB = startLab[2]

        targetState = newState

        animator.addUpdateListener { anim ->
            val target = targetState ?: return@addUpdateListener
            val fraction = (anim.animatedValue as Float).toDouble()

            val targetLab = DoubleArray(3)
            ColorUtils.colorToLAB(target.shapeColor, targetLab)

            val interpColor =
                ColorUtils.LABToColor(
                    lerp(startL, targetLab[0], fraction),
                    lerp(startA, targetLab[1], fraction),
                    lerp(startB, targetLab[2], fraction),
                )

            updateColor(ColorState(target.chipColor, 45f, interpColor))
        }

        animator.addListener(
            object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) {
                        targetState?.let { updateColor(it) }
                    }
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
        currentState = null
    }

    private fun updateColor(state: ColorState) {
        currentState = state
        callback.onShapeRenderModelUpdated { model ->
            if (model is ShapeRenderModel.WithShape) {
                model.copy(shapeColor = state.shapeColor)
            } else model
        }
    }

    companion object {
        private fun lerp(start: Double, end: Double, fraction: Double): Double =
            start + (end - start) * fraction
    }
}
