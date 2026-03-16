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

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.min

object RectUtils {

    private const val EPSILON = 1e-4f

    fun areAlmostEqual(a: RectF, b: RectF): Boolean {
        return abs(a.left - b.left) < EPSILON &&
            abs(a.top - b.top) < EPSILON &&
            abs(a.right - b.right) < EPSILON &&
            abs(a.bottom - b.bottom) < EPSILON
    }

    fun bottomSquare(rect: RectF): RectF {
        val side = min(rect.width(), rect.height())
        val left = rect.centerX() - side / 2f
        return RectF(left, rect.bottom - side, left + side, rect.bottom)
    }

    fun largestCenteredRect(rect: RectF, aspectRatio: Float): RectF {
        val w = min(rect.width(), rect.height() * aspectRatio)
        val h = min(rect.width() / aspectRatio, rect.height())
        return RectF(
            rect.centerX() - w * 0.5f,
            rect.centerY() - h * 0.5f,
            rect.centerX() + w * 0.5f,
            rect.centerY() + h * 0.5f,
        )
    }

    fun scale(rect: RectF, factor: Float): RectF {
        return scaleAround(rect, PointF(rect.centerX(), rect.centerY()), factor)
    }

    fun scaleAround(rect: RectF, center: PointF, factor: Float): RectF {
        return RectF(
            center.x + (rect.left - center.x) * factor,
            center.y + (rect.top - center.y) * factor,
            center.x + (rect.right - center.x) * factor,
            center.y + (rect.bottom - center.y) * factor,
        )
    }
}
