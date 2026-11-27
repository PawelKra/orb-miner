package com.example.spheresweeper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.spheresweeper.R
import com.example.spheresweeper.logic.GameStatus
import com.example.spheresweeper.logic.MinesweeperBoard
import com.example.spheresweeper.logic.SphereGeometry
import com.example.spheresweeper.ui.theme.SpaceColors
import kotlinx.coroutines.delay

@Composable
fun GameScreen(vm: GameViewModel) {
    BackHandler { vm.backToMenu() }

    val geometry = vm.geometry
    val board = vm.board
    val status = vm.status
    val finished = status == GameStatus.WON || status == GameStatus.LOST

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val viewInsets = rememberBallInsets(landscape)

        SpaceBackground()

        if (geometry != null && board != null) {
            SphereView(
                vm = vm,
                geometry = geometry,
                board = board,
                viewInsets = viewInsets,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = stringResource(R.string.loading),
                style = MaterialTheme.typography.titleMedium,
                color = SpaceColors.TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Hud(vm = vm, landscape = landscape)

        if (status == GameStatus.READY && board != null) {
            Text(
                text = stringResource(if (vm.flagByLongPress) R.string.hint_long_press else R.string.hint_double_tap),
                style = MaterialTheme.typography.bodySmall,
                color = SpaceColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }

        // Show the result card shortly after the game ends so the explosion or confetti is visible first.
        var showResult by remember(vm.gameId) { mutableStateOf(false) }
        LaunchedEffect(vm.gameId, status) {
            if (status == GameStatus.WON || status == GameStatus.LOST) {
                delay(if (status == GameStatus.LOST) 900L else 600L)
                showResult = true
            } else {
                showResult = false
            }
        }

        // In landscape the card sits in the free strip right of the ball instead of covering it.
        AnimatedVisibility(
            visible = showResult && finished,
            modifier = Modifier
                .align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut(),
        ) {
            ResultCard(
                won = status == GameStatus.WON,
                timeMillis = vm.finalTimeMillis,
                newBest = vm.isNewBest,
                bestMillis = vm.bestTimes[vm.boardSize],
                compact = landscape,
                onPlayAgain = { vm.restart() },
                onMenu = { vm.backToMenu() },
            )
        }
    }
}

/**
 * Margins the ball has to stay clear of: system bars, the HUD (top row in portrait, side columns in
 * landscape) and the hint line at the bottom. The hint band is always reserved so the ball does not
 * jump when the hint disappears after the first move.
 */
@Composable
private fun rememberBallInsets(landscape: Boolean): ViewInsets {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val safeLeft = safe.getLeft(density, layoutDirection).toFloat()
    val safeTop = safe.getTop(density).toFloat()
    val safeRight = safe.getRight(density, layoutDirection).toFloat()
    val safeBottom = safe.getBottom(density).toFloat()
    val hintFontSize = MaterialTheme.typography.bodySmall.fontSize
    return remember(landscape, density, safeLeft, safeTop, safeRight, safeBottom, hintFontSize) {
        with(density) {
            val hintLines = if (landscape) 1 else 2
            val hintBand = HINT_VERTICAL_PADDING.toPx() * 2f + hintFontSize.toPx() * HINT_LINE_FACTOR * hintLines
            if (landscape) {
                val side = (HUD_EDGE_PADDING + HUD_SIDE_COLUMN_WIDTH).toPx()
                ViewInsets(
                    left = safeLeft + side,
                    top = safeTop + HUD_EDGE_PADDING.toPx(),
                    right = safeRight + side,
                    bottom = safeBottom + hintBand,
                )
            } else {
                ViewInsets(
                    left = safeLeft,
                    top = safeTop + HUD_ROW_HEIGHT.toPx(),
                    right = safeRight,
                    bottom = safeBottom + hintBand,
                )
            }
        }
    }
}

private val HUD_EDGE_PADDING = 16.dp
private val HUD_ROW_HEIGHT = 64.dp // 48 dp buttons plus 8 dp padding above and below
private val HUD_SIDE_COLUMN_WIDTH = 112.dp
private val HINT_VERTICAL_PADDING = 16.dp
private const val HINT_LINE_FACTOR = 1.4f

@Composable
private fun SphereView(
    vm: GameViewModel,
    geometry: SphereGeometry,
    board: MinesweeperBoard,
    viewInsets: ViewInsets,
    modifier: Modifier = Modifier,
) {
    val renderer = remember(geometry) { SphereRenderer(geometry) }
    val haptics = rememberHaptics(vm.hapticsEnabled)
    val scope = rememberCoroutineScope()
    val controller = remember(vm, geometry) { SphereInputController(vm, geometry, scope) }
    val flagByLongPress = vm.flagByLongPress
    SideEffect {
        controller.flagByLongPress = flagByLongPress
        controller.haptics = haptics
        controller.viewInsets = viewInsets
    }

    // Frame ticks only while a one-shot effect (explosion, confetti) is running.
    var animTick by remember { mutableIntStateOf(0) }
    val effect = vm.effect
    val effectStart = vm.effectStartNanos
    LaunchedEffect(effect, effectStart) {
        if (effect != SphereEffect.NONE) {
            val duration = SphereRenderer.effectDurationNanos(effect)
            while (System.nanoTime() - effectStart < duration) {
                withFrameNanos { animTick++ }
            }
            animTick++
        }
    }

    Canvas(
        modifier
            .graphicsLayer { }
            .pointerInput(controller) { controller.handleGestures(this) },
    ) {
        // Reading these states subscribes only the draw phase to them.
        vm.viewVersion
        vm.boardVersion
        animTick
        renderer.draw(
            scope = this,
            orientation = vm.orientation,
            zoom = vm.zoom,
            insets = viewInsets,
            board = board,
            effect = vm.effect,
            effectStartNanos = vm.effectStartNanos,
            nowNanos = System.nanoTime(),
        )
    }
}

@Composable
private fun Hud(vm: GameViewModel, landscape: Boolean) {
    val mines = vm.minesRemaining
    val timeText = formatSeconds(vm.elapsedSeconds)
    val minesDescription = stringResource(R.string.hud_mines_remaining, mines)
    val timeDescription = stringResource(R.string.hud_time, timeText)
    val menuDescription = stringResource(R.string.action_menu)
    val restartDescription = stringResource(R.string.action_restart)

    val menuButton: @Composable () -> Unit = {
        GlowIconButton(
            onClick = { vm.backToMenu() },
            contentDescription = menuDescription,
            accent = SpaceColors.Violet,
            icon = { drawMenuIcon(it) },
        )
    }
    val restartButton: @Composable () -> Unit = {
        GlowIconButton(
            onClick = { vm.restart() },
            contentDescription = restartDescription,
            accent = SpaceColors.Cyan,
            icon = { drawRestartIcon(it) },
        )
    }
    val minesPill: @Composable () -> Unit = {
        StatPill(
            value = mines.toString(),
            contentDescription = minesDescription,
            accent = SpaceColors.Danger,
            icon = { drawMineIcon(it) },
        )
    }
    val timePill: @Composable () -> Unit = {
        StatPill(
            value = timeText,
            contentDescription = timeDescription,
            accent = SpaceColors.Gold,
            icon = { drawClockIcon(it) },
        )
    }

    if (landscape) {
        // Side columns keep the HUD away from the ball, which is centred in the wider dimension.
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.align(Alignment.TopStart),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                menuButton()
                minesPill()
            }
            Column(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                restartButton()
                timePill()
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            menuButton()
            Spacer(Modifier.weight(1f))
            minesPill()
            Spacer(Modifier.width(10.dp))
            timePill()
            Spacer(Modifier.weight(1f))
            restartButton()
        }
    }
}

@Composable
private fun ResultCard(
    won: Boolean,
    timeMillis: Long,
    newBest: Boolean,
    bestMillis: Long?,
    compact: Boolean,
    onPlayAgain: () -> Unit,
    onMenu: () -> Unit,
) {
    val accent = if (won) SpaceColors.Success else SpaceColors.Danger
    val shape = RoundedCornerShape(if (compact) 20.dp else 24.dp)
    Column(
        modifier = Modifier
            .widthIn(max = if (compact) 272.dp else 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(SpaceColors.Panel.copy(alpha = 0.9f))
            .border(1.dp, accent.copy(alpha = 0.6f), shape)
            .padding(if (compact) 14.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(if (won) R.string.result_won else R.string.result_lost),
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(if (won) R.string.result_won_subtitle else R.string.result_lost_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = SpaceColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.result_time, formatDuration(timeMillis)),
            style = MaterialTheme.typography.titleMedium,
            color = SpaceColors.TextPrimary,
        )
        if (won && newBest) {
            Text(
                text = stringResource(R.string.result_new_best),
                style = MaterialTheme.typography.titleMedium,
                color = SpaceColors.Gold,
            )
        } else if (bestMillis != null) {
            Text(
                text = stringResource(R.string.best_time, formatDuration(bestMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = SpaceColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            // In the narrow card the longer label gets more room so it stays on one line.
            GlowTextButton(
                text = stringResource(R.string.action_play_again),
                onClick = onPlayAgain,
                modifier = Modifier.weight(if (compact) 1.4f else 1f),
                primary = true,
                accent = accent,
            )
            GlowTextButton(
                text = stringResource(R.string.action_menu_short),
                onClick = onMenu,
                modifier = Modifier.weight(1f),
                primary = false,
                accent = SpaceColors.Violet,
            )
        }
    }
}
