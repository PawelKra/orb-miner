package com.example.spheresweeper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.spheresweeper.ui.theme.SpaceColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val ButtonShape = RoundedCornerShape(16.dp)
private val PillShape = RoundedCornerShape(50)

/** Soft glow drawn just outside the bounds, behind a rounded control. */
private fun Modifier.outerGlow(color: Color, corner: Dp, spread: Dp): Modifier = drawBehind {
    val s = spread.toPx()
    val c = corner.toPx()
    for (step in 1..3) {
        val grow = s * step / 3f
        drawRoundRect(
            color = color,
            topLeft = Offset(-grow, -grow),
            size = Size(size.width + grow * 2f, size.height + grow * 2f),
            cornerRadius = CornerRadius(c + grow, c + grow),
            alpha = 0.10f / step,
        )
    }
}

/** Rounded translucent icon button with a glowing accent border; the icon is drawn by [icon]. */
@Composable
fun GlowIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    accent: Color = SpaceColors.Cyan,
    icon: DrawScope.(Color) -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .outerGlow(accent, 16.dp, 6.dp)
            .clip(ButtonShape)
            .background(
                Brush.verticalGradient(
                    listOf(SpaceColors.PanelTop.copy(alpha = 0.85f), SpaceColors.Panel.copy(alpha = 0.7f)),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.6f), ButtonShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) { icon(accent) }
    }
}

/** Wide text button; [primary] buttons are filled with the accent gradient. */
@Composable
fun GlowTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    accent: Color = SpaceColors.Cyan,
) {
    val background = if (primary) {
        Brush.horizontalGradient(listOf(accent.copy(alpha = 0.9f), SpaceColors.Violet.copy(alpha = 0.9f)))
    } else {
        Brush.verticalGradient(listOf(SpaceColors.PanelTop.copy(alpha = 0.85f), SpaceColors.Panel.copy(alpha = 0.7f)))
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .outerGlow(accent, 16.dp, if (primary) 8.dp else 4.dp)
            .clip(ButtonShape)
            .background(background)
            .border(1.dp, accent.copy(alpha = if (primary) 0.9f else 0.5f), ButtonShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) Color(0xFF071022) else SpaceColors.TextPrimary,
        )
    }
}

/** Compact translucent pill with an icon and a tabular-number value. */
@Composable
fun StatPill(
    value: String,
    contentDescription: String,
    accent: Color,
    modifier: Modifier = Modifier,
    icon: DrawScope.(Color) -> Unit,
) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(SpaceColors.Panel.copy(alpha = 0.72f))
            .border(1.dp, accent.copy(alpha = 0.45f), PillShape)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(18.dp)) { icon(accent) }
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = TextStyle(
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
                color = SpaceColors.TextPrimary,
            ),
        )
    }
}

// ---------------------------------------------------------------------- canvas icons

/** Four rounded squares: "menu". */
fun DrawScope.drawMenuIcon(color: Color) {
    val gap = size.minDimension * 0.16f
    val cell = (size.minDimension - gap) / 2f
    val r = CornerRadius(cell * 0.3f, cell * 0.3f)
    for (row in 0..1) {
        for (col in 0..1) {
            drawRoundRect(
                color = color,
                topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                size = Size(cell, cell),
                cornerRadius = r,
                style = Stroke(width = size.minDimension * 0.1f),
            )
        }
    }
}

/** Circular arrow: "restart". */
fun DrawScope.drawRestartIcon(color: Color) {
    val stroke = size.minDimension * 0.12f
    val radius = size.minDimension * 0.36f
    val c = center
    val startAngle = -60f
    val sweep = 290f
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(c.x - radius, c.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    // Arrow head at the end of the arc, pointing along the clockwise tangent.
    val end = ((startAngle + sweep) * PI / 180.0).toFloat()
    val px = c.x + cos(end) * radius
    val py = c.y + sin(end) * radius
    val tx = -sin(end)
    val ty = cos(end)
    val nx = cos(end)
    val ny = sin(end)
    val a = size.minDimension * 0.2f
    val head = Path().apply {
        moveTo(px + tx * a, py + ty * a)
        lineTo(px + nx * a * 0.8f, py + ny * a * 0.8f)
        lineTo(px - nx * a * 0.8f, py - ny * a * 0.8f)
        close()
    }
    drawPath(head, color)
}

/** Spiked ball: "mine". */
fun DrawScope.drawMineIcon(color: Color) {
    val c = center
    val r = size.minDimension * 0.5f
    for (k in 0 until 8) {
        val angle = (k * PI / 4.0).toFloat()
        val dx = cos(angle)
        val dy = sin(angle)
        drawLine(
            color = color,
            start = Offset(c.x + dx * r * 0.3f, c.y + dy * r * 0.3f),
            end = Offset(c.x + dx * r * 0.95f, c.y + dy * r * 0.95f),
            strokeWidth = r * 0.16f,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(color = color, radius = r * 0.55f, center = c)
    drawCircle(color = Color.White.copy(alpha = 0.8f), radius = r * 0.14f, center = Offset(c.x - r * 0.18f, c.y - r * 0.18f))
}

/** Clock face: "time". */
fun DrawScope.drawClockIcon(color: Color) {
    val c = center
    val r = size.minDimension * 0.44f
    val stroke = size.minDimension * 0.11f
    drawCircle(color = color, radius = r, center = c, style = Stroke(width = stroke))
    drawLine(color = color, start = c, end = Offset(c.x, c.y - r * 0.6f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color = color, start = c, end = Offset(c.x + r * 0.45f, c.y), strokeWidth = stroke, cap = StrokeCap.Round)
}

/** Small red flag on a pole. */
fun DrawScope.drawFlagIcon(color: Color) {
    val w = size.minDimension
    val poleX = w * 0.3f
    drawLine(
        color = SpaceColors.TextPrimary,
        start = Offset(poleX, w * 0.92f),
        end = Offset(poleX, w * 0.08f),
        strokeWidth = w * 0.1f,
        cap = StrokeCap.Round,
    )
    val cloth = Path().apply {
        moveTo(poleX, w * 0.08f)
        lineTo(w * 0.9f, w * 0.3f)
        lineTo(poleX, w * 0.52f)
        close()
    }
    drawPath(cloth, color)
}
