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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.spheresweeper.R
import com.example.spheresweeper.logic.BoardFeature
import com.example.spheresweeper.logic.Campaign
import com.example.spheresweeper.logic.CampaignProgress
import com.example.spheresweeper.logic.GameStatus
import com.example.spheresweeper.logic.LevelSpec
import com.example.spheresweeper.logic.MinesweeperBoard
import com.example.spheresweeper.logic.RecordResult
import com.example.spheresweeper.logic.SphereGeometry
import com.example.spheresweeper.ui.theme.LocalGameTheme
import com.example.spheresweeper.ui.theme.themeColors
import kotlinx.coroutines.delay

@Composable
fun GameScreen(vm: GameViewModel) {
    BackHandler { vm.exitGame() }
    // Registered later, so while feature cards are open back closes the card instead of the game.
    val featureCards = vm.featureCards
    BackHandler(enabled = featureCards != null) { vm.closeFeatureCard() }

    val geometry = vm.geometry
    val board = vm.board
    val status = vm.status
    val finished = status == GameStatus.WON || status == GameStatus.LOST
    val config = vm.config
    val level = (config as? GameConfig.Level)?.spec
    val colors = themeColors

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val hint = hintText(vm)
        val viewInsets = rememberBallInsets(landscape, withLevelLabel = level != null, hint = hint, screenWidth = maxWidth)

        ThemeBackground()

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
                color = colors.textSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Hud(vm = vm, landscape = landscape)

        // The game opens already PLAYING on a revealed start area; the help text stays until the first move.
        val playerMoved = vm.playerMoved

        // Portrait: lives and hints use the band reserved for the help text, so they appear after the first move.
        val showLives = config.lives > 1
        val showHints = config.hints > 0
        if (!landscape && (showLives || showHints) && status == GameStatus.PLAYING && playerMoved) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showLives) LivesPill(vm.livesLeft)
                if (showHints) HintPill(vm)
            }
        }

        if (board != null && status == GameStatus.PLAYING && !playerMoved) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = HINT_HORIZONTAL_PADDING, vertical = HINT_VERTICAL_PADDING),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (level != null) {
                    Text(
                        text = levelLabel(level),
                        style = MaterialTheme.typography.titleSmall,
                        color = branchTextColor(level.branch, colors),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(LEVEL_LABEL_GAP))
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
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
            when (config) {
                is GameConfig.Free -> ResultCard(
                    won = status == GameStatus.WON,
                    timeMillis = vm.finalTimeMillis,
                    newBest = vm.isNewBest,
                    bestMillis = vm.bestTimes[config.size],
                    compact = landscape,
                    onPlayAgain = { vm.restart() },
                    onMenu = { vm.backToMenu() },
                )
                is GameConfig.Level -> LevelResultCard(
                    level = config.spec,
                    won = status == GameStatus.WON,
                    record = vm.lastRecord,
                    timeMillis = vm.finalTimeMillis,
                    hasNext = vm.nextLevel != null,
                    compact = landscape,
                    onNext = { vm.startNextLevel() },
                    onPlayAgain = { vm.restart() },
                    onMap = { vm.exitGame() },
                )
            }
        }

        if (featureCards != null && board != null) {
            FeatureCardOverlay(cards = featureCards, landscape = landscape, onClose = { vm.closeFeatureCard() })
        }
    }
}

/**
 * One feature card over the dimmed game: a heading ("New" for an intro, "On this board" for cards opened
 * from the HUD), "2/3" when there are several cards, then the legend drawing and text of the feature
 * from the "How to play" screen and a button to the next card or back to the game. The scrim takes
 * every touch, so the ball neither turns nor takes moves while the card is open. In landscape the
 * drawing sits beside the text so that the card fits the low screen.
 */
@Composable
private fun FeatureCardOverlay(cards: FeatureCards, landscape: Boolean, onClose: () -> Unit) {
    val colors = themeColors
    val shape = RoundedCornerShape(24.dp)
    val feature = cards.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundBottom.copy(alpha = 0.6f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        // A new card starts scrolled to its top.
        key(cards.index, feature) {
            val cardModifier = Modifier
                .widthIn(max = if (landscape) 640.dp else 420.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(colors.panel.copy(alpha = 0.95f))
                .border(1.dp, colors.primary.copy(alpha = 0.6f), shape)
            if (landscape) {
                Row(
                    modifier = cardModifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FeatureDrawing(feature, Modifier.weight(1f), maxHeight = FEATURE_DRAWING_MAX_HEIGHT)
                    Spacer(Modifier.width(16.dp))
                    Column(
                        Modifier
                            .weight(1.3f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        FeatureCardHeader(cards)
                        Spacer(Modifier.height(8.dp))
                        FeatureText(feature)
                        Spacer(Modifier.height(14.dp))
                        FeatureCardButton(cards, onClose)
                    }
                }
            } else {
                Column(
                    cardModifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    FeatureCardHeader(cards)
                    Spacer(Modifier.height(10.dp))
                    FeatureDrawing(feature, maxHeight = FEATURE_DRAWING_MAX_HEIGHT)
                    Spacer(Modifier.height(10.dp))
                    FeatureText(feature)
                    Spacer(Modifier.height(16.dp))
                    FeatureCardButton(cards, onClose)
                }
            }
        }
    }
}

/** "New" or "On this board", with the card counter on the right when there are several cards. */
@Composable
private fun FeatureCardHeader(cards: FeatureCards) {
    val colors = themeColors
    val count = cards.features.size
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(if (cards.intro) R.string.feature_card_new else R.string.feature_card_board),
            style = MaterialTheme.typography.titleSmall,
            color = colors.gold,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (count > 1) {
            val counterDescription = stringResource(R.string.feature_card_counter_desc, cards.index + 1, count)
            Text(
                text = stringResource(R.string.feature_card_counter, cards.index + 1, count),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
                modifier = Modifier.semantics { contentDescription = counterDescription },
            )
        }
    }
}

/** "Next" while more cards follow, "OK" on the last one. */
@Composable
private fun FeatureCardButton(cards: FeatureCards, onClose: () -> Unit) {
    val last = cards.index == cards.features.size - 1
    GlowTextButton(
        text = stringResource(if (last) R.string.feature_card_ok else R.string.feature_card_next),
        onClick = onClose,
        modifier = Modifier.fillMaxWidth(),
        primary = true,
        accent = themeColors.primary,
    )
}

/** Tallest feature drawing on a card; the far cell's drawing would otherwise take most of the screen. */
private val FEATURE_DRAWING_MAX_HEIGHT = 180.dp

/**
 * Question mark: "what is on this board". The hook path and the stroke are built for the icon size of
 * the first draw and kept until that size changes, so redrawing the icon allocates nothing.
 */
private class QuestionIcon {
    private val hook = Path()
    private var stroke = Stroke()
    private var builtForSize = -1f

    fun draw(scope: DrawScope, color: Color) {
        with(scope) {
            val w = size.minDimension
            if (w != builtForSize) {
                builtForSize = w
                stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                // Hook: three quarters of a circle from its left point over the top, ending below its centre.
                hook.reset()
                hook.arcTo(Rect(w * 0.29f, w * 0.1f, w * 0.71f, w * 0.52f), 180f, 270f, forceMoveTo = true)
                hook.lineTo(w * 0.5f, w * 0.66f)
            }
            drawPath(hook, color, style = stroke)
            drawCircle(color = color, radius = w * 0.075f, center = Offset(w * 0.5f, w * 0.88f))
        }
    }
}

/** The help line under the ball: the controls, with the far cell tip only on boards that have far cells. */
@Composable
private fun hintText(vm: GameViewModel): String {
    val controls = stringResource(if (vm.flagByLongPress) R.string.hint_long_press else R.string.hint_double_tap)
    if (BoardFeature.FAR !in vm.boardFeatures) return controls
    return "$controls · ${stringResource(R.string.hint_far_scope)}"
}

/** "Walls · 7/20": branch name and the level's place in it. */
@Composable
private fun levelLabel(level: LevelSpec): String = stringResource(
    R.string.campaign_level_label,
    stringResource(branchNameRes(level.branch)),
    level.number,
    Campaign.levels(level.branch).size,
)

/**
 * Margins the ball has to stay clear of: system bars, the HUD (top row in portrait, side columns in
 * landscape) and the help line [hint] at the bottom, with the level label above it when [withLevelLabel].
 * The band is always reserved so the ball does not jump when the hint disappears after the first move.
 *
 * The band is as tall as [hint] laid out in the theme font across [screenWidth], less the safe insets
 * and the side padding of the help line, so a line that wraps (a long text, a wide font or a large
 * system font size) never runs over the ball. It is never lower than one line in landscape, or two in
 * portrait, where the lives and hints row takes the band after the first move.
 */
@Composable
private fun rememberBallInsets(landscape: Boolean, withLevelLabel: Boolean, hint: String, screenWidth: Dp): ViewInsets {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val safeLeft = safe.getLeft(density, layoutDirection).toFloat()
    val safeTop = safe.getTop(density).toFloat()
    val safeRight = safe.getRight(density, layoutDirection).toFloat()
    val safeBottom = safe.getBottom(density).toFloat()
    val hintStyle = MaterialTheme.typography.bodySmall
    val labelFontSize = MaterialTheme.typography.titleSmall.fontSize
    val textMeasurer = rememberTextMeasurer()
    val hintWidth = with(density) {
        (screenWidth.toPx() - safeLeft - safeRight - 2f * HINT_HORIZONTAL_PADDING.toPx()).toInt().coerceAtLeast(0)
    }
    val hintHeight = remember(textMeasurer, hint, hintStyle, hintWidth) {
        textMeasurer.measure(
            text = AnnotatedString(hint),
            style = hintStyle,
            constraints = Constraints(maxWidth = hintWidth),
        ).size.height
    }
    return remember(
        landscape, withLevelLabel, density, safeLeft, safeTop, safeRight, safeBottom, hintStyle, labelFontSize, hintHeight,
    ) {
        with(density) {
            val minLines = if (landscape) 1 else 2
            val minHintHeight = hintStyle.fontSize.toPx() * HINT_LINE_FACTOR * minLines
            val labelBand = if (withLevelLabel) labelFontSize.toPx() * HINT_LINE_FACTOR + LEVEL_LABEL_GAP.toPx() else 0f
            val hintBand = HINT_VERTICAL_PADDING.toPx() * 2f + maxOf(minHintHeight, hintHeight.toFloat()) + labelBand
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
private val HINT_HORIZONTAL_PADDING = 24.dp
private val LEVEL_LABEL_GAP = 4.dp
private const val HINT_LINE_FACTOR = 1.4f

@Composable
private fun SphereView(
    vm: GameViewModel,
    geometry: SphereGeometry,
    board: MinesweeperBoard,
    viewInsets: ViewInsets,
    modifier: Modifier = Modifier,
) {
    val theme = LocalGameTheme.current
    val context = LocalContext.current
    val renderer = remember(geometry, theme) {
        SphereRenderer(geometry, theme.sphere, theme.font.canvasTypeface(context))
    }
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
            effectCell = vm.effectCell,
            layout = vm.layout,
            highlightCell = vm.highlightCell,
        )
    }
}

@Composable
private fun LivesPill(lives: Int, modifier: Modifier = Modifier) {
    StatPill(
        value = lives.toString(),
        contentDescription = stringResource(R.string.hud_lives_remaining, lives),
        accent = themeColors.heart,
        modifier = modifier,
        icon = { drawHeartIcon(it) },
    )
}

@Composable
private fun HintPill(vm: GameViewModel) {
    val haptics = rememberHaptics(vm.hapticsEnabled)
    val hints = vm.hintsLeft
    StatPill(
        value = hints.toString(),
        contentDescription = stringResource(R.string.action_hint, hints),
        accent = themeColors.primary,
        onClick = { haptics.perform(vm.useHint()) },
        enabled = hints > 0 && vm.status == GameStatus.PLAYING,
        icon = { drawBulbIcon(it) },
    )
}

@Composable
private fun Hud(vm: GameViewModel, landscape: Boolean) {
    val mines = vm.minesRemaining
    val timeText = formatSeconds(vm.elapsedSeconds)
    val minesDescription = stringResource(R.string.hud_mines_remaining, mines)
    val timeDescription = stringResource(R.string.hud_time, timeText)
    val menuDescription = stringResource(if (vm.config is GameConfig.Level) R.string.action_map else R.string.action_menu)
    val restartDescription = stringResource(R.string.action_restart)
    val helpDescription = stringResource(R.string.action_feature_help)
    val colors = themeColors
    val questionIcon = remember { QuestionIcon() }
    // Boards with plain numbers and flags only have nothing to explain beyond the help line.
    val showHelp = vm.board != null && vm.boardFeatures.isNotEmpty()

    val menuButton: @Composable () -> Unit = {
        GlowIconButton(
            onClick = { vm.exitGame() },
            contentDescription = menuDescription,
            accent = colors.secondary,
            icon = { drawMenuIcon(it) },
        )
    }
    val restartButton: @Composable () -> Unit = {
        GlowIconButton(
            onClick = { vm.restart() },
            contentDescription = restartDescription,
            accent = colors.primary,
            icon = { drawRestartIcon(it) },
        )
    }
    val helpButton: @Composable (Modifier) -> Unit = { modifier ->
        GlowIconButton(
            onClick = { vm.openFeatureHelp() },
            contentDescription = helpDescription,
            modifier = modifier,
            accent = colors.gold,
            icon = { questionIcon.draw(this, it) },
        )
    }
    val minesPill: @Composable () -> Unit = {
        StatPill(
            value = mines.toString(),
            contentDescription = minesDescription,
            accent = colors.danger,
            icon = { drawMineIcon(it) },
        )
    }
    val timePill: @Composable () -> Unit = {
        StatPill(
            value = timeText,
            contentDescription = timeDescription,
            accent = colors.gold,
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
                if (vm.config.lives > 1) LivesPill(vm.livesLeft)
            }
            Column(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                restartButton()
                timePill()
                if (vm.config.hints > 0) HintPill(vm)
                if (showHelp) helpButton(Modifier)
            }
        }
    } else {
        // The top row has no room for a fifth control on a 360 dp screen, so the help button sits just
        // below the restart button, in the corner the round ball leaves free.
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
            if (showHelp) {
                helpButton(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = HUD_ROW_HEIGHT, end = 16.dp),
                )
            }
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
    val colors = themeColors
    val accent = if (won) colors.success else colors.danger
    val shape = RoundedCornerShape(if (compact) 20.dp else 24.dp)
    Column(
        modifier = Modifier
            .widthIn(max = if (compact) 272.dp else 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panel.copy(alpha = 0.9f))
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
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.result_time, formatDuration(timeMillis)),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
        if (won && newBest) {
            Text(
                text = stringResource(R.string.result_new_best),
                style = MaterialTheme.typography.titleMedium,
                color = colors.gold,
            )
        } else if (bestMillis != null) {
            Text(
                text = stringResource(R.string.best_time, formatDuration(bestMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
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
                accent = colors.secondary,
            )
        }
    }
}

/**
 * Result of a campaign level: the stars of this game, the best stars when they are higher, and the way
 * on. After a win with a next level the card offers it first; otherwise it offers a replay and the map.
 */
@Composable
private fun LevelResultCard(
    level: LevelSpec,
    won: Boolean,
    record: RecordResult?,
    timeMillis: Long,
    hasNext: Boolean,
    compact: Boolean,
    onNext: () -> Unit,
    onPlayAgain: () -> Unit,
    onMap: () -> Unit,
) {
    val colors = themeColors
    val accent = if (won) colors.success else colors.danger
    val shape = RoundedCornerShape(if (compact) 20.dp else 24.dp)
    val earned = record?.earned ?: 0
    val best = record?.best ?: earned
    val maxStars = CampaignProgress.MAX_STARS
    val gap = if (compact) 8.dp else 12.dp
    val earnedDescription = stringResource(R.string.result_stars_desc, earned, maxStars)
    Column(
        modifier = Modifier
            .widthIn(max = if (compact) 272.dp else 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panel.copy(alpha = 0.9f))
            .border(1.dp, accent.copy(alpha = 0.6f), shape)
            .padding(if (compact) 14.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(if (won) R.string.result_won else R.string.result_lost),
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
        )
        Text(
            text = levelLabel(level),
            style = MaterialTheme.typography.bodyMedium,
            color = branchTextColor(level.branch, colors),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        StarRow(
            stars = earned,
            starSize = if (compact) 26.dp else 32.dp,
            modifier = Modifier.semantics { contentDescription = earnedDescription },
        )
        if (record != null && record.improved) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.result_new_star_record),
                style = MaterialTheme.typography.titleMedium,
                color = colors.gold,
            )
        } else if (best > earned) {
            Spacer(Modifier.height(4.dp))
            val bestDescription = stringResource(R.string.result_best_stars_desc, best, maxStars)
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = bestDescription },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.result_best_stars),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.width(6.dp))
                StarRow(stars = best, starSize = 14.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.result_time, formatDuration(timeMillis)),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
        if (won && hasNext) {
            GlowTextButton(
                text = stringResource(R.string.action_next_level),
                onClick = onNext,
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                accent = accent,
            )
            Spacer(Modifier.height(gap))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            // Without a next level the map is the way on after a win, and a replay after a loss.
            GlowTextButton(
                text = stringResource(R.string.action_play_again),
                onClick = onPlayAgain,
                modifier = Modifier.weight(if (compact) 1.4f else 1f),
                primary = !won,
                accent = accent,
            )
            GlowTextButton(
                text = stringResource(R.string.action_map_short),
                onClick = onMap,
                modifier = Modifier.weight(1f),
                primary = won && !hasNext,
                accent = colors.secondary,
            )
        }
    }
}
