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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.spheresweeper.R
import com.example.spheresweeper.logic.BoardSize
import com.example.spheresweeper.logic.Campaign
import com.example.spheresweeper.logic.CampaignProgress
import com.example.spheresweeper.logic.SphereGeometry
import com.example.spheresweeper.math.Quaternion
import com.example.spheresweeper.ui.theme.GameTheme
import com.example.spheresweeper.ui.theme.GameThemes
import com.example.spheresweeper.ui.theme.LocalGameTheme
import com.example.spheresweeper.ui.theme.UiPalette
import com.example.spheresweeper.ui.theme.numberTextStyle
import com.example.spheresweeper.ui.theme.themeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MenuScreen(vm: GameViewModel) {
    val orbGeometry = remember { vm.geometryFor(BoardSize.SMALL) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        ThemeBackground()

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
                    CampaignCard(progress = vm.progress, onClick = { vm.openCampaign() })
                    Spacer(Modifier.height(20.dp))
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
                    CampaignCard(progress = vm.progress, onClick = { vm.openCampaign() })
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
    val colors = themeColors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MenuOrb(geometry, Modifier.size(orbSize))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.menu_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.primary.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
    }
}

/** Slowly spinning decorative ball drawn with the game renderer in the current theme. */
@Composable
private fun MenuOrb(geometry: SphereGeometry, modifier: Modifier = Modifier) {
    val theme = LocalGameTheme.current
    val context = LocalContext.current
    val renderer = remember(geometry, theme) {
        SphereRenderer(geometry, theme.sphere, theme.font.canvasTypeface(context))
    }
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
    SectionTitle(stringResource(R.string.menu_free_play))
    val sizes = BoardSize.values()
    val colors = themeColors
    for (index in sizes.indices) {
        val boardSize = sizes[index]
        BoardCard(
            boardSize = boardSize,
            accent = boardAccent(index, sizes.size, colors),
            best = vm.bestTimes[boardSize],
            onClick = { vm.startGame(boardSize) },
        )
        if (index < sizes.size - 1) Spacer(Modifier.height(10.dp))
    }
}

/** Badge colour of board [index] of [count]: runs through the theme's board accents. */
private fun boardAccent(index: Int, count: Int, colors: UiPalette): Color {
    val stops = colors.boardAccents
    if (stops.size == 1) return stops[0]
    val t = if (count <= 1) 0f else index.toFloat() / (count - 1)
    val scaled = t * (stops.size - 1)
    val k = minOf(stops.size - 2, scaled.toInt())
    return lerp(stops[k], stops[k + 1], scaled - k)
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
        color = themeColors.textMuted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 10.dp),
    )
}

@Composable
private fun BoardCard(boardSize: BoardSize, accent: Color, best: Long?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val colors = themeColors
    val resources = LocalContext.current.resources
    val cells = resources.getQuantityString(R.plurals.cells_count, boardSize.cellCount, boardSize.cellCount)
    // With ocean the mine count follows the drawn land, so the card shows the count for the expected land.
    val mineCount = boardSize.expectedMines
    val mines = resources.getQuantityString(
        if (boardSize.islands > 0) R.plurals.mines_count_approx else R.plurals.mines_count,
        mineCount, mineCount,
    )
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
                    listOf(accent.copy(alpha = 0.16f), colors.panel.copy(alpha = 0.75f)),
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
                color = colors.textPrimary,
            )
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (best != null) {
                Text(
                    text = stringResource(R.string.best_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                Text(
                    text = formatDuration(best),
                    style = numberTextStyle(16f, colors.gold),
                )
            } else {
                Text(
                    text = stringResource(R.string.best_time_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

/** Entry to the campaign map with the completed levels and the stars collected so far. */
@Composable
private fun CampaignCard(progress: CampaignProgress, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val colors = themeColors
    val accent = colors.gold
    val levelCount = Campaign.all.size
    val completed = Campaign.all.count { progress.isCompleted(it) }
    val stars = progress.totalStars
    val maxStars = levelCount * CampaignProgress.MAX_STARS
    val starsDescription = stringResource(R.string.campaign_stars_total_desc, stars, maxStars)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.18f), colors.secondary.copy(alpha = 0.14f), colors.panel.copy(alpha = 0.75f)),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.55f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(38.dp)) { drawStarIcon(accent) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.campaign_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Text(
                text = stringResource(R.string.campaign_card_subtitle, completed, levelCount),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.campaign_stars_total, stars, maxStars),
            style = numberTextStyle(16f, accent),
            modifier = Modifier.semantics { contentDescription = starsDescription },
        )
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
    Spacer(Modifier.height(16.dp))
    ThemePicker(selected = vm.theme, onSelect = { vm.updateTheme(it) })
}

/**
 * Theme choice: one tile per registered theme, drawn in that theme's own colours and font, with its
 * name, a "123" sample in its number colours and dots of its ball palette. The tiles form a radio
 * group for accessibility services; a choice applies at once.
 */
@Composable
private fun ThemePicker(selected: GameTheme, onSelect: (GameTheme) -> Unit) {
    Text(
        text = stringResource(R.string.setting_theme),
        style = MaterialTheme.typography.titleMedium,
        color = themeColors.textPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 8.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (row in GameThemes.all.chunked(THEME_COLUMNS)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (theme in row) {
                    ThemeTile(
                        theme = theme,
                        selected = theme.id == selected.id,
                        onClick = { onSelect(theme) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // A short last row keeps the tile width of the full rows.
                for (k in row.size until THEME_COLUMNS) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One theme in its own background, font and colours; the selected tile has a thick accent border. */
@Composable
private fun ThemeTile(theme: GameTheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val ui = theme.ui
    val sphere = theme.sphere
    val font = theme.font
    val name = stringResource(theme.nameRes)
    val description = stringResource(R.string.setting_theme_desc, name)
    val numbers = sphere.numbers
    val southCovered = sphere.covered.first()
    val northCovered = sphere.covered.last()
    val dots = listOf(
        Color(southCovered.r, southCovered.g, southCovered.b),
        Color(northCovered.r, northCovered.g, northCovered.b),
        Color(sphere.flag),
        Color(sphere.far),
    )
    Column(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(ui.backgroundTop, ui.backgroundBottom)))
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) ui.primary else ui.panelBorder.copy(alpha = 0.8f),
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = TextStyle(
                    color = ui.textPrimary,
                    fontSize = (17f * font.scale).sp,
                    fontFamily = font.family,
                    fontWeight = font.weight ?: FontWeight.SemiBold,
                ),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            RadioMark(selected = selected, color = ui.primary, ring = ui.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(sphere.revealedSwatch))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                for (n in 1..3) {
                    Text(
                        text = n.toString(),
                        style = TextStyle(
                            color = Color(numbers[minOf(n, numbers.size - 1)]),
                            fontSize = (18f * font.scale).sp,
                            fontFamily = font.family,
                            fontWeight = font.weight ?: FontWeight.Bold,
                            fontFeatureSettings = "tnum, lnum",
                        ),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            for (dot in dots) {
                Spacer(Modifier.width(4.dp))
                Canvas(Modifier.size(12.dp)) {
                    drawCircle(dot)
                    drawCircle(ui.textPrimary.copy(alpha = 0.25f), style = Stroke(width = 1.dp.toPx()))
                }
            }
        }
    }
}

/** Radio indicator of a theme tile: a ring, filled in [color] when [selected]. */
@Composable
private fun RadioMark(selected: Boolean, color: Color, ring: Color) {
    Canvas(Modifier.size(18.dp)) {
        val r = size.minDimension / 2f
        val stroke = 1.8.dp.toPx()
        drawCircle(if (selected) color else ring, radius = r - stroke / 2f, style = Stroke(width = stroke))
        if (selected) drawCircle(color, radius = r * 0.5f)
    }
}

/** Theme tiles per row in the settings. */
private const val THEME_COLUMNS = 2

@Composable
private fun SettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val colors = themeColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panel.copy(alpha = 0.7f))
            .border(1.dp, colors.panelBorder.copy(alpha = 0.7f), shape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.switchThumb,
                checkedTrackColor = colors.primary,
                checkedBorderColor = colors.primary,
                uncheckedThumbColor = colors.textSecondary,
                uncheckedTrackColor = colors.backgroundMid,
                uncheckedBorderColor = colors.panelBorder,
            ),
        )
    }
}
