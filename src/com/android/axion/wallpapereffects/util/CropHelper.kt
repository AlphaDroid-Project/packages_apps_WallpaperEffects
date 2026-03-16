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

package com.android.axion.wallpapereffects.util

import android.graphics.Matrix
import android.graphics.RectF
import android.util.Size
import kotlin.math.min
import kotlin.math.sqrt

object CropHelper {

    fun centerAlign(
        displaySize: Size,
        bitmapSize: Size,
        addParallax: Boolean = false,
        isRtl: Boolean = false,
    ): RectF {
        val scale =
            min(
                bitmapSize.width.toFloat() / displaySize.width,
                bitmapSize.height.toFloat() / displaySize.height,
            )
        val rect = RectF(0f, 0f, displaySize.width * scale, displaySize.height * scale)
        rect.offset(
            (bitmapSize.width - rect.width()) / 2f,
            (bitmapSize.height - rect.height()) / 2f,
        )
        if (addParallax) {
            rect.set(addWidthForParallax(rect, bitmapSize, isRtl))
        }
        return rect
    }

    fun addWidthForParallax(crop: RectF, bitmapSize: Size, isRtl: Boolean): RectF {
        val result = RectF(crop)
        val parallaxWidth = crop.width() * 0.3f
        if (isRtl) {
            if (crop.left > 0f) {
                result.left -= min(crop.left, parallaxWidth)
            }
        } else {
            if (crop.right < bitmapSize.width) {
                result.right += min(bitmapSize.width - crop.right, parallaxWidth)
            }
        }
        return result
    }

    fun applyParallax(xOffset: Float, crop: RectF, displaySize: Size): RectF {
        val visibleWidth = (crop.height() * displaySize.width) / displaySize.height
        val left = crop.left + (crop.width() - visibleWidth) * xOffset
        return RectF(left, crop.top, left + visibleWidth, crop.bottom)
    }

    fun getCropMatrix(scale: Float, crop: RectF, displaySize: Size): Matrix {
        val matrix = Matrix()
        val s = (displaySize.width / crop.width()) * scale
        val tx = displaySize.width / 2f - crop.centerX() * s
        val ty = displaySize.height / 2f - crop.centerY() * s
        matrix.postScale(s, s)
        matrix.postTranslate(tx, ty)
        return matrix
    }

    fun getAdjustedCrop(
        bitmapSize: Size,
        displaySize: Size,
        crop: RectF,
        addParallax: Boolean,
        mode: Int = 3,
    ): RectF {
        val result = RectF(crop)
        val cropAspect = crop.width() / crop.height()
        val displayAspect = displaySize.width.toFloat() / displaySize.height

        if (cropAspect == displayAspect) return crop

        if (cropAspect > displayAspect) {
            if (addParallax) {
                val excess = (cropAspect / displayAspect) - 1f
                if (excess > 0.3f) {
                    val trim = (crop.height() * ((excess - 0.3f) * displayAspect)) / 2f
                    result.left += trim
                    result.right -= trim
                }
                return result
            }

            return result
        }

        val targetWidth =
            when (mode) {
                1 -> crop.height() * displayAspect - crop.width()
                2 -> 0f
                else -> -crop.width() + sqrt(crop.height() * crop.width() * displayAspect)
            }

        if (bitmapSize.width - crop.width() >= targetWidth) {
            var leftExpand = targetWidth / 2f
            var rightExpand = targetWidth / 2f

            if (crop.left < leftExpand) {
                rightExpand += leftExpand - crop.left
                leftExpand = crop.left
            } else if (bitmapSize.width - crop.right < rightExpand) {
                leftExpand += rightExpand - (bitmapSize.width - crop.right)
                rightExpand = bitmapSize.width - crop.right
            }
            result.left -= leftExpand
            result.right += rightExpand
        } else {
            result.left = min(0f, result.left)
            result.right = maxOf(bitmapSize.width.toFloat(), result.right)
        }

        val heightTrim = (crop.height() - result.width() / displayAspect) / 2f
        result.top += heightTrim
        result.bottom -= heightTrim
        return result
    }
}
