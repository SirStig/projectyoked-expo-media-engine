package com.projectyoked.mediaengine

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * TextureRenderer
 * 
 * Handles rendering of an OES External Texture (video frame) to the current EGL surface.
 * Supports matrix transformations for rotation/scaling and basic color filters.
 */
class TextureRenderer {
    private val TAG = "TextureRenderer"

    private val mTriangleVerticesData = floatArrayOf(
        // X, Y, Z, U, V
        -1.0f, -1.0f, 0f, 0f, 0f,
        1.0f, -1.0f, 0f, 1f, 0f,
        -1.0f, 1.0f, 0f, 0f, 1f,
        1.0f, 1.0f, 0f, 1f, 1f
    )

    private val mTriangleVertices: FloatBuffer
    private val mMVPMatrix = FloatArray(16)
    private val mSTMatrix = FloatArray(16)
    private var mProgram = 0
    private var mTextureID = -12345
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0
    private var mFilterTypeHandle = 0

    // simple vertex shader
    private val mVertexShader = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
          gl_Position = uMVPMatrix * aPosition;
          vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """

    // fragment shader with external texture support and simple filters
    private val mFragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        uniform int uFilterType; // 0=None, 1=Grayscale, 2=Sepia
        
        void main() {
            vec4 color = texture2D(sTexture, vTextureCoord);
            
            if (uFilterType == 1) { // Grayscale
                // Rec. 709 Luma coefficients
                float gray = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
                gl_FragColor = vec4(gray, gray, gray, color.a);
            } else if (uFilterType == 2) { // Sepia
                float r = dot(color.rgb, vec3(0.393, 0.769, 0.189));
                float g = dot(color.rgb, vec3(0.349, 0.686, 0.168));
                float b = dot(color.rgb, vec3(0.272, 0.534, 0.131));
                gl_FragColor = vec4(min(r, 1.0), min(g, 1.0), min(b, 1.0), color.a);
            } else {
                gl_FragColor = color;
            }
        }
    """

    init {
        mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        mTriangleVertices.put(mTriangleVerticesData).position(0)
        Matrix.setIdentityM(mSTMatrix, 0)
    }

    fun init() {
        surfaceCreated()
    }
    
    fun createTextureID(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        mTextureID = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID)
        
        // Needed for OES textures
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        return mTextureID
    }

    private var mProgram2D = 0
    private var maPositionHandle2D = 0
    private var maTextureHandle2D = 0
    private var muMVPMatrixHandle2D = 0
    
    // Shader for GL_TEXTURE_2D (Standard, non-OES)
    private val mFragmentShader2D = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D sTexture;
        
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """

    // ...

    fun surfaceCreated() {
        // ... (Existing OES Program Init) ...
        mProgram = createProgram(mVertexShader, mFragmentShader)
        if (mProgram == 0) throw RuntimeException("failed creating program OES")
        
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition")
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord")
        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix")
        mFilterTypeHandle = GLES20.glGetUniformLocation(mProgram, "uFilterType")

        // 2. Init 2D Program (Overlay)
        mProgram2D = createProgram(mVertexShader, mFragmentShader2D)
        if (mProgram2D == 0) throw RuntimeException("failed creating program 2D")
        
        maPositionHandle2D = GLES20.glGetAttribLocation(mProgram2D, "aPosition")
        maTextureHandle2D = GLES20.glGetAttribLocation(mProgram2D, "aTextureCoord")
        muMVPMatrixHandle2D = GLES20.glGetUniformLocation(mProgram2D, "uMVPMatrix")
        // No STMatrix needed for 2D, but vertex shader expects it. We can bind identity.
        // Actually, let's just reuse the vertex shader and bind identity to uSTMatrix.
    }

    fun draw(textureId: Int, stMatrix: FloatArray?, mvpMatrix: FloatArray?, filterType: Int) {
        checkGlError("onDrawFrame start")
        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        mTriangleVertices.position(0)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, mTriangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")

        mTriangleVertices.position(3)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, mTriangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")

        if (mvpMatrix != null) {
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0)
        } else {
            Matrix.setIdentityM(mMVPMatrix, 0)
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        }
        
        // Use ST Matrix from SurfaceTexture if provided, otherwise identity
        if (stMatrix != null) {
            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, stMatrix, 0)
        } else {
             GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mMVPMatrix, 0)
        }
        
        GLES20.glUniform1i(mFilterTypeHandle, filterType)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
    }

    fun drawOverlay(textureId: Int, x: Float, y: Float, scaleX: Float, scaleY: Float) {
        GLES20.glUseProgram(mProgram2D)
        checkGlError("glUseProgram2D")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        
        // Disable blending if not needed, but overlays (text) usually alpha blend
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        mTriangleVertices.position(0)
        GLES20.glVertexAttribPointer(maPositionHandle2D, 3, GLES20.GL_FLOAT, false, 5 * 4, mTriangleVertices)
        GLES20.glEnableVertexAttribArray(maPositionHandle2D)

        mTriangleVertices.position(3)
        GLES20.glVertexAttribPointer(maTextureHandle2D, 2, GLES20.GL_FLOAT, false, 5 * 4, mTriangleVertices)
        GLES20.glEnableVertexAttribArray(maTextureHandle2D)
        
        // Calculate MVP Matrix for Overlay
        // x,y are normalized centers (-1 to 1)
        // scale is relative to viewport
        Matrix.setIdentityM(mMVPMatrix, 0)
        Matrix.translateM(mMVPMatrix, 0, x, y, 0f)
        Matrix.scaleM(mMVPMatrix, 0, scaleX, scaleY, 1f)
        
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle2D, 1, false, mMVPMatrix, 0)
        
        // For uSTMatrix in Vertex Shader (reused), pass identity
        val flipMatrix = FloatArray(16)
        Matrix.setIdentityM(flipMatrix, 0)
        Matrix.translateM(flipMatrix, 0, 0f, 1f, 0f)
        Matrix.scaleM(flipMatrix, 0, 1f, -1f, 1f)
        
        val uSTHandle = GLES20.glGetUniformLocation(mProgram2D, "uSTMatrix")
        GLES20.glUniformMatrix4fv(uSTHandle, 1, false, flipMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES20.glDisable(GLES20.GL_BLEND)
    }
    
    fun draw2D(textureId: Int, mvpMatrix: FloatArray?, filterType: Int) {
        GLES20.glUseProgram(mProgram2D)
        checkGlError("glUseProgram2D")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        
        // Use blending? Images might have alpha.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        mTriangleVertices.position(0)
        GLES20.glVertexAttribPointer(maPositionHandle2D, 3, GLES20.GL_FLOAT, false, 5 * 4, mTriangleVertices)
        GLES20.glEnableVertexAttribArray(maPositionHandle2D)

        mTriangleVertices.position(3)
        GLES20.glVertexAttribPointer(maTextureHandle2D, 2, GLES20.GL_FLOAT, false, 5 * 4, mTriangleVertices)
        GLES20.glEnableVertexAttribArray(maTextureHandle2D)
        
        if (mvpMatrix != null) {
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle2D, 1, false, mvpMatrix, 0)
        } else {
            Matrix.setIdentityM(mMVPMatrix, 0)
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle2D, 1, false, mMVPMatrix, 0)
        }
        
        // For uSTMatrix in Vertex Shader (reused), pass identity
        val flipMatrix = FloatArray(16)
        Matrix.setIdentityM(flipMatrix, 0)
        // Standard Images are Top-Down? No, Loaded via BitmapFactory -> GLUtils.texImage2D
        // GLUtils.texImage2D loads bitmap. Bitmap 0,0 is top-left.
        // GL 0,0 is bottom-left.
        // So yes, we likely need to flip Y for standard images too unless we want them upside down.
        Matrix.translateM(flipMatrix, 0, 0f, 1f, 0f)
        Matrix.scaleM(flipMatrix, 0, 1f, -1f, 1f)
        
        val uSTHandle = GLES20.glGetUniformLocation(mProgram2D, "uSTMatrix")
        GLES20.glUniformMatrix4fv(uSTHandle, 1, false, flipMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        if (shader != 0) {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader $shaderType:")
                Log.e(TAG, GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                shader = 0
            }
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) return 0
        var program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            checkGlError("glAttachShader")
            GLES20.glAttachShader(program, pixelShader)
            checkGlError("glAttachShader")
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ")
                Log.e(TAG, GLES20.glGetProgramInfoLog(program))
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }
        return program
    }

    fun checkGlError(op: String) {
        var error: Int
        while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$op: glError $error")
            throw RuntimeException("$op: glError $error")
        }
    }
}
