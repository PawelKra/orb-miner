package com.example.spheresweeper.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.example.spheresweeper.ui.theme.SpaceColors
import kotlin.math.sin
import kotlin.random.Random

/** Precomputed star positions (normalised 0..1), sizes and twinkle parameters. */
private class StarField(seed: Int, val count: Int) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    val radiusDp = FloatArray(count)
    val brightness = FloatArray(count)
    val phase = FloatArray(count)
    val speed = FloatArray(count)
    val tint = IntArray(count)

    init {
        val random = Random(seed)
        for (i in 0 until count) {
            x[i] = random.nextFloat()
            y[i] = random.nextFloat()
            val big = random.nextFloat() < 0.08f
            radiusDp[i] = if (big) 1.3f + random.nextFloat() * 0.9f else 0.4f + random.nextFloat() * 0.8f
            brightness[i] = 0.35f + random.nextFloat() * 0.65f
            phase[i] = random.nextFloat() * 6.2831855f
            speed[i] = 0.6f + random.nextFloat() * 2.2f
            val t = random.nextFloat()
            tint[i] = if (t < 0.15f) 1 else if (t < 0.25f) 2 else 0
        }
    }

    fun draw(scope: DrawScope, time: Float) {
        val w = scope.size.width
        val h = scope.size.height
        val density = scope.density
        for (i in 0 until count) {
            val twinkle = 0.65f + 0.35f * sin(time * speed[i] + phase[i])
            val alpha = brightness[i] * twinkle
            val color = when (tint[i]) {
                1 -> STAR_BLUE
                2 -> STAR_WARM
                else -> STAR_WHITE
            }
            val r = radiusDp[i] * density
            val center = Offset(x[i] * w, y[i] * h)
            if (radiusDp[i] > 1.2f) {
                scope.drawCircle(color = color, radius = r * 3.2f, center = center, alpha = alpha * 0.12f)
            }
            scope.drawCircle(color = color, radius = r, center = center, alpha = alpha)
        }
    }

    private companion object {
        val STAR_WHITE = Color(0xFFF2F5FF)
        val STAR_BLUE = Color(0xFFA9C8FF)
        val STAR_WARM = Color(0xFFFFE2B8)
    }
}

/**
 * Deep-space backdrop: vertical gradient, two faint nebulae and a slowly twinkling starfield.
 * Twinkling is throttled to about 30 updates per second and drawn in its own layer.
 */
@Composable
fun SpaceBackground(modifier: Modifier = Modifier) {
    val stars = remember { StarField(seed = 1337, count = 170) }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var lastUpdate = start
        while (true) {
            withFrameNanos { now ->
                if (now - lastUpdate >= TWINKLE_FRAME_NANOS) {
                    lastUpdate = now
                    time = (now - start) / 1e9f
                }
            }
        }
    }
    Spacer(
        modifier
            .fillMaxSize()
            .graphicsLayer { }
            .drawWithCache {
                val gradient = Brush.verticalGradient(
                    colors = listOf(SpaceColors.SpaceTop, SpaceColors.SpaceMid, SpaceColors.SpaceBottom),
                )
                val nebulaA = Brush.radialGradient(
                    colors = listOf(SpaceColors.NebulaViolet.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.22f),
                    radius = size.maxDimension * 0.55f,
                )
                val nebulaB = Brush.radialGradient(
                    colors = listOf(SpaceColors.NebulaTeal.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.8f),
                    radius = size.maxDimension * 0.5f,
                )
                onDrawBehind {
                    drawRect(gradient)
                    drawRect(nebulaA)
                    drawRect(nebulaB)
                    stars.draw(this, time)
                }
            },
    )
}

private const val TWINKLE_FRAME_NANOS = 33_000_000L
