package com.example.foodify.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Camera preview only. Diagnostic depth maps are separate, never overlaid unaligned. */
class CameraBackground {
    var textureId = 0
        private set
    private var program = 0
    private val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val positions = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val uv = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun create() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec2 aPosition; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position=vec4(aPosition,0.0,1.0); vUv=aUv; }")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES uCamera; varying vec2 vUv; void main(){ gl_FragColor=texture2D(uCamera,vUv); }")
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Camera shader link failed" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        positions.put(vertices).position(0)
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Camera shader compile failed" }
        return id
    }

    fun draw(frame: Frame) {
        val coords = FloatArray(8)
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            vertices, Coordinates2d.TEXTURE_NORMALIZED, coords)
        uv.position(0)
        uv.put(coords).position(0)
        positions.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCamera"), 0)
        val p = GLES20.glGetAttribLocation(program, "aPosition")
        val t = GLES20.glGetAttribLocation(program, "aUv")
        GLES20.glEnableVertexAttribArray(p)
        GLES20.glEnableVertexAttribArray(t)
        GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(t, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p)
        GLES20.glDisableVertexAttribArray(t)
    }
}
