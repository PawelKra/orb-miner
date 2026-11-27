package com.example.spheresweeper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.spheresweeper.R
import com.example.spheresweeper.logic.BoardSize
import com.example.spheresweeper.logic.SphereGeometry
import com.example.spheresweeper.math.Quaternion
import com.example.spheresweeper.ui.theme.SpaceColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MenuScreen(vm: GameViewModel) {
    val orbGeometry = remember { vm.geometryFor(BoardSize.SMALL) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        SpaceBackground()

        if (landscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MenuHeader(orbGeometry, orbSize = 140.dp)
                    Spacer(Modifier.height(20.dp))
                    SettingsSection(vm)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                ) {
                    BoardList(vm)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MenuHeader(orbGeometry, orbSize = 180.dp)
                    Spacer(Modifier.height(20.dp))
                    BoardList(vm)
                    Spacer(Modifier.height(24.dp))
                    SettingsSection(vm)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(geometry: SphereGeometry, orbSize: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MenuOrb(geometry, Modifier.size(orbSize))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = SpaceColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.menu_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = SpaceColors.Cyan.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
    }
}

/** Slowly spinning decorative ball drawn with the game renderer. */
@Composable
private fun MenuOrb(geometry: SphereGeometry, modifier: Modifier = Modifier) {
    val renderer = remember(geometry) { SphereRenderer(geometry) }
    val orientation = remember { Quaternion().setFromAxisAngle(1f, 0.3f, 0f, 0.6f) }
    val step = remember { Quaternion() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                step.setFromAxisAngle(0.2f, 1f, 0.15f, dt * 0.45f)
                orientation.preMultiply(step).normalize()
                tick++
            }
        }
    }
    Canvas(modifier.graphicsLayer { }) {
        tick
        renderer.draw(
            scope = this,
            orientation = orientation,
            // Slightly zoomed out so the halo stays inside the orb's slot in the menu layout.
            zoom = 0.9f,
            insets = ViewInsets.ZERO,
            board = null,
            effect = SphereEffect.NONE,
            effectStartNanos = 0L,
            nowNanos = 0L,
        )
    }
}

@Composable
private fun ColumnScope.BoardList(vm: GameViewModel) {
    SectionTitle(stringResource(R.string.menu_choose_board))
    val sizes = BoardSize.values()
    for (index in sizes.indices) {
        val boardSize = sizes[index]
        BoardCard(
            boardSize = boardSize,
            accent = boardAccent(index, sizes.size),
            best = vm.bestTimes[boardSize],
            onClick = { vm.startGame(boardSize) },
        )
        if (index < sizes.size - 1) Spacer(Modifier.height(10.dp))
    }
}

private fun boardAccent(index: Int, count: Int): Color {
    val t = if (count <= 1) 0f else index.toFloat() / (count - 1)
    return if (t < 0.5f) {
        lerp(Color(0xFF2EE6D6), Color(0xFF4F8BFF), t * 2f)
    } else {
        lerp(Color(0xFF4F8BFF), Color(0xFFB07BFF), (t - 0.5f) * 2f)
    }
}

private fun boardNameRes(size: BoardSize): Int = when (size) {
    BoardSize.TINY -> R.string.board_tiny
    BoardSize.SMALL -> R.string.board_small
    BoardSize.MEDIUM -> R.string.board_medium
    BoardSize.LARGE -> R.string.board_large
    BoardSize.HUGE -> R.string.board_huge
    BoardSize.GIANT -> R.string.board_giant
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.titleSmall,
        color = SpaceColors.TextMuted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 10.dp),
    )
}

@Composable
private fun BoardCard(boardSize: BoardSize, accent: Color, best: Long?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val resources = LocalContext.current.resources
    val cells = resources.getQuantityString(R.plurals.cells_count, boardSize.cellCount, boardSize.cellCount)
    val mines = resources.getQuantityString(R.plurals.mines_count, boardSize.mines, boardSize.mines)
    val details = if (boardSize.lives > 1) {
        val lives = resources.getQuantityString(R.plurals.lives_count, boardSize.lives, boardSize.lives)
        stringResource(R.string.board_details_lives, cells, mines, lives)
    } else {
        stringResource(R.string.board_details, cells, mines)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.16f), SpaceColors.Panel.copy(alpha = 0.75f)),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HexBadge(accent, Modifier.size(38.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(boardNameRes(boardSize)),
                style = MaterialTheme.typography.titleMedium,
                color = SpaceColors.TextPrimary,
            )
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = SpaceColors.TextSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (best != null) {
                Text(
                    text = stringResource(R.string.best_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = SpaceColors.TextMuted,
                )
                Text(
                    text = formatDuration(best),
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        color = SpaceColors.Gold,
                    ),
                )
            } else {
                Text(
                    text = stringResource(R.string.best_time_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = SpaceColors.TextMuted,
                )
            }
        }
    }
}

/** Glowing hexagon used as the board-size badge. */
@Composable
private fun HexBadge(accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = center
        val outer = size.minDimension * 0.48f
        val inner = outer * 0.55f
        val outerPath = Path()
        val innerPath = Path()
        for (k in 0 until 6) {
            val angle = (PI / 6.0 + k * PI / 3.0).toFloat()
            val ox = c.x + cos(angle) * outer
            val oy = c.y + sin(angle) * outer
            val ix = c.x + cos(angle) * inner
            val iy = c.y + sin(angle) * inner
            if (k == 0) {
                outerPath.moveTo(ox, oy)
                innerPath.moveTo(ix, iy)
            } else {
                outerPath.lineTo(ox, oy)
                innerPath.lineTo(ix, iy)
            }
        }
        outerPath.close()
        innerPath.close()
        drawPath(
            outerPath,
            Brush.radialGradient(
                listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f)),
                center = Offset(c.x - outer * 0.3f, c.y - outer * 0.3f),
                radius = outer * 1.6f,
            ),
        )
        drawPath(outerPath, accent, style = Stroke(width = 1.5.dp.toPx()))
        drawPath(innerPath, accent.copy(alpha = 0.85f))
    }
}

@Composable
private fun ColumnScope.SettingsSection(vm: GameViewModel) {
    SectionTitle(stringResource(R.string.settings_title))
    SettingRow(
        title = stringResource(R.string.setting_long_press),
        description = stringResource(R.string.setting_long_press_desc),
        checked = vm.flagByLongPress,
        onCheckedChange = { vm.updateFlagByLongPress(it) },
    )
    Spacer(Modifier.height(10.dp))
    SettingRow(
        title = stringResource(R.string.setting_haptics),
        description = stringResource(R.string.setting_haptics_desc),
        checked = vm.hapticsEnabled,
        onCheckedChange = { vm.updateHapticsEnabled(it) },
    )
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SpaceColors.Panel.copy(alpha = 0.7f))
            .border(1.dp, SpaceColors.PanelBorder.copy(alpha = 0.7f), shape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = SpaceColors.TextPrimary)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = SpaceColors.TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF04161C),
                checkedTrackColor = SpaceColors.Cyan,
                checkedBorderColor = SpaceColors.Cyan,
                uncheckedThumbColor = SpaceColors.TextSecondary,
                uncheckedTrackColor = SpaceColors.SpaceMid,
                uncheckedBorderColor = SpaceColors.PanelBorder,
            ),
        )
    }
}
