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

import android.content.Context
import android.opengl.GLES30
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "ShaderUtils"

fun loadRawResource(context: Context, resId: Int): String {
    val inputStream = context.resources.openRawResource(resId)
    val reader = BufferedReader(InputStreamReader(inputStream))
    return reader.use { it.readText() }
}

fun compileShader(type: Int, source: String): Int {
    val shader = GLES30.glCreateShader(type)
    if (shader == 0) {
        Log.e(TAG, "Failed to create shader of type $type")
        return 0
    }
    GLES30.glShaderSource(shader, source)
    GLES30.glCompileShader(shader)

    val status = IntArray(1)
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES30.glGetShaderInfoLog(shader)
        Log.e(TAG, "Shader compile error: $log")
        GLES30.glDeleteShader(shader)
        return 0
    }
    return shader
}

fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
    if (vertexShader == 0) return 0

    val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
    if (fragmentShader == 0) {
        GLES30.glDeleteShader(vertexShader)
        return 0
    }

    val program = GLES30.glCreateProgram()
    if (program == 0) {
        Log.e(TAG, "Failed to create program")
        return 0
    }

    GLES30.glAttachShader(program, vertexShader)
    GLES30.glAttachShader(program, fragmentShader)
    GLES30.glLinkProgram(program)

    val status = IntArray(1)
    GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES30.glGetProgramInfoLog(program)
        Log.e(TAG, "Program link error: $log")
        GLES30.glDeleteProgram(program)
        return 0
    }

    GLES30.glDeleteShader(vertexShader)
    GLES30.glDeleteShader(fragmentShader)
    return program
}
