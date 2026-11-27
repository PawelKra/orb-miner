package com.example.spheresweeper.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mutable unit quaternion (w + xi + yj + zk) used for the ball orientation.
 *
 * All operations write into this instance (or into caller-provided arrays) so they can be
 * used inside per-frame code without allocating.
 */
class Quaternion(
    var w: Float = 1f,
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
) {

    fun set(w: Float, x: Float, y: Float, z: Float): Quaternion {
        this.w = w
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun set(other: Quaternion): Quaternion = set(other.w, other.x, other.y, other.z)

    fun setIdentity(): Quaternion = set(1f, 0f, 0f, 0f)

    /** Rotation of [angle] radians around the axis (ax, ay, az); the axis does not need to be normalised. */
    fun setFromAxisAngle(ax: Float, ay: Float, az: Float, angle: Float): Quaternion {
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (len < 1e-8f) return setIdentity()
        val half = angle * 0.5f
        val s = sin(half) / len
        return set(cos(half), ax * s, ay * s, az * s)
    }

    fun length(): Float = sqrt(w * w + x * x + y * y + z * z)

    /** Normalises in place; falls back to identity for a degenerate quaternion. */
    fun normalize(): Quaternion {
        val len = length()
        if (len < 1e-8f) return setIdentity()
        val inv = 1f / len
        return set(w * inv, x * inv, y * inv, z * inv)
    }

    /** this = a * b (apply b first, then a). Safe when a or b is this instance. */
    fun setMultiply(a: Quaternion, b: Quaternion): Quaternion {
        val nw = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
        val nx = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y
        val ny = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x
        val nz = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w
        return set(nw, nx, ny, nz)
    }

    /** this = q * this, i.e. rotation [q] is applied after the current rotation (in world space). */
    fun preMultiply(q: Quaternion): Quaternion = setMultiply(q, this)

    /** this = this * q, i.e. rotation [q] is applied before the current rotation (in model space). */
    fun postMultiply(q: Quaternion): Quaternion = setMultiply(this, q)

    /** Rotates vector (vx, vy, vz) by this quaternion and writes it to out[offset..offset+2]. */
    fun rotate(vx: Float, vy: Float, vz: Float, out: FloatArray, offset: Int = 0) {
        rotateInternal(x, y, z, vx, vy, vz, out, offset)
    }

    /** Rotates vector (vx, vy, vz) by the inverse (conjugate) of this unit quaternion. */
    fun rotateInverse(vx: Float, vy: Float, vz: Float, out: FloatArray, offset: Int = 0) {
        rotateInternal(-x, -y, -z, vx, vy, vz, out, offset)
    }

    // v' = v + 2w (u x v) + 2 u x (u x v), with u = (ux, uy, uz)
    private fun rotateInternal(
        ux: Float, uy: Float, uz: Float,
        vx: Float, vy: Float, vz: Float,
        out: FloatArray, offset: Int,
    ) {
        val tx = 2f * (uy * vz - uz * vy)
        val ty = 2f * (uz * vx - ux * vz)
        val tz = 2f * (ux * vy - uy * vx)
        out[offset] = vx + w * tx + (uy * tz - uz * ty)
        out[offset + 1] = vy + w * ty + (uz * tx - ux * tz)
        out[offset + 2] = vz + w * tz + (ux * ty - uy * tx)
    }

    /** Writes the row-major 3x3 rotation matrix of this unit quaternion into out[0..8]. */
    fun toRotationMatrix(out: FloatArray) {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        out[0] = 1f - 2f * (yy + zz)
        out[1] = 2f * (xy - wz)
        out[2] = 2f * (xz + wy)
        out[3] = 2f * (xy + wz)
        out[4] = 1f - 2f * (xx + zz)
        out[5] = 2f * (yz - wx)
        out[6] = 2f * (xz - wy)
        out[7] = 2f * (yz + wx)
        out[8] = 1f - 2f * (xx + yy)
    }

    override fun toString(): String = "Quaternion(w=$w, x=$x, y=$y, z=$z)"
}
