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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
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
import com.example.spheresweeper.logic.MatchEngine
import com.example.spheresweeper.logic.MinesweeperBoard
import com.example.spheresweeper.logic.RecordResult
import com.example.spheresweeper.logic.SphereGeometry
import com.example.spheresweeper.ui.theme.LocalGameTheme
import com.example.spheresweeper.ui.theme.numberTextStyle
import com.example.spheresweeper.ui.theme.themeColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GameScreen(vm: GameViewModel) {
    // Leaving or restarting a match under way waits for the players to confirm; null while nothing is asked.
    var pendingExit by remember(vm.gameId) { mutableStateOf<MatchExit?>(null) }
    val exitGame: () -> Unit = {
        if (vm.matchInProgress) {
            pendingExit = MatchExit.LEAVE
        } else {
            vm.exitGame()
        }
    }
    val restart: () -> Unit = {
        if (vm.matchInProgress) {
            pendingExit = MatchExit.RESTART
        } else {
            vm.restart()
        }
    }
    BackHandler { exitGame() }
    // Registered later, so while feature cards are open back closes the card instead of the game.
    val featureCards = vm.featureCards
    BackHandler(enabled = featureCards != null) { vm.closeFeatureCard() }
    // Registered last: while the question is open, back closes it and the match goes on.
    BackHandler(enabled = pendingExit != null) { pendingExit = null }

    val geometry = vm.geometry
    val board = vm.board
    val status = vm.status
    val config = vm.config
    val match = config as? GameConfig.Match
    // A match ends with its last safe cell, when its board is solved.
    val finished = if (match != null) vm.matchOver else status == GameStatus.WON || status == GameStatus.LOST
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

        Hud(vm = vm, landscape = landscape, onExit = exitGame, onRestart = restart)

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

        // In a match the band of the help line names the player to move once the first move is made.
        if (match != null && board != null && playerMoved && !finished) {
            MatchTurnLine(
                vm = vm,
                movesPerTurn = match.movesPerTurn,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = HINT_HORIZONTAL_PADDING, vertical = HINT_VERTICAL_PADDING),
            )
        }

        // Show the result card shortly after the game ends so the explosion or confetti is visible first.
        var showResult by remember(vm.gameId) { mutableStateOf(false) }
        LaunchedEffect(vm.gameId, status, finished) {
            if (finished) {
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
                is GameConfig.Match -> MatchResultCard(
                    leader = vm.matchLeader,
                    firstScore = vm.matchScore(0),
                    secondScore = vm.matchScore(1),
                    unownedMines = vm.matchUnownedMines,
                    compact = landscape,
                    onPlayAgain = { vm.restart() },
                    onMenu = { vm.backToMenu() },
                )
            }
        }

        if (featureCards != null && board != null) {
            FeatureCardOverlay(cards = featureCards, landscape = landscape, onClose = { vm.closeFeatureCard() })
        }

        val handoffTo = vm.handoffTo
        if (handoffTo >= 0 && board != null) {
            HandoffOverlay(player = handoffTo, onContinue = { vm.confirmHandoff() })
        }

        when (pendingExit) {
            MatchExit.LEAVE -> ConfirmOverlay(
                title = stringResource(R.string.match_leave_title),
                text = stringResource(R.string.match_leave_text),
                confirm = stringResource(R.string.match_leave_confirm),
                onConfirm = {
                    pendingExit = null
                    vm.exitGame()
                },
                onDismiss = { pendingExit = null },
            )
            MatchExit.RESTART -> ConfirmOverlay(
                title = stringResource(R.string.match_restart_title),
                text = stringResource(R.string.match_restart_text),
                confirm = stringResource(R.string.match_restart_confirm),
                onConfirm = {
                    pendingExit = null
                    vm.restart()
                },
                onDismiss = { pendingExit = null },
            )
            null -> Unit
        }
    }
}

/** What the players asked for while a match is under way: leave the game or restart it. */
private enum class MatchExit { LEAVE, RESTART }

/**
 * Question over the dimmed game, with a [confirm] button and a button that keeps playing ([onDismiss]).
 * The scrim takes every touch, so the ball neither turns nor takes moves while the question is open.
 */
@Composable
private fun ConfirmOverlay(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = themeColors
    val shape = RoundedCornerShape(24.dp)
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
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(colors.panel.copy(alpha = 0.95f))
                .border(1.dp, colors.danger.copy(alpha = 0.6f), shape)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlowTextButton(
                    text = stringResource(R.string.match_keep_playing),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    primary = true,
                    accent = colors.primary,
                )
                GlowTextButton(
                    text = confirm,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    primary = false,
                    accent = colors.danger,
                )
            }
        }
    }
}

/** Whose turn it is, in the player's colour, and with several moves per turn the moves left. */
@Composable
private fun MatchTurnLine(vm: GameViewModel, movesPerTurn: Int, modifier: Modifier = Modifier) {
    val player = vm.matchPlayer
    val text = if (movesPerTurn > 1) {
        stringResource(R.string.match_turn_moves, player + 1, vm.matchMovesLeft, movesPerTurn)
    } else {
        stringResource(R.string.match_turn, player + 1)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = themeColors.players[player],
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/**
 * Panel between two turns of a match: "Pass the phone" and the next [player] in their colour. It covers
 * the whole screen with a translucent scrim, so the shared board stays visible, and takes every touch:
 * the ball neither turns nor takes moves until the next player taps the panel. Taps in the first
 * [HANDOFF_ARM_MILLIS] are ignored, so a quick extra tap of the player who just moved does not close it.
 */
@Composable
private fun HandoffOverlay(player: Int, onContinue: () -> Unit) {
    val colors = themeColors
    val accent = colors.players[player]
    val shape = RoundedCornerShape(24.dp)
    val onContinueLatest by rememberUpdatedState(onContinue)
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(HANDOFF_ARM_MILLIS)
        armed = true
    }
    val tapLabel = stringResource(R.string.match_handoff_tap)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundBottom.copy(alpha = HANDOFF_SCRIM_ALPHA))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        // The last finger lifted completes a tap on the panel.
                        if (armed && event.type == PointerEventType.Release && event.changes.none { it.pressed }) {
                            onContinueLatest()
                        }
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                onClick(label = tapLabel) {
                    if (armed) onContinueLatest()
                    armed
                }
            }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(colors.panel.copy(alpha = 0.95f))
                .border(2.dp, accent, shape)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.match_handoff_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(28.dp)) { drawPlayerHex(accent, filled = true) }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.match_player, player + 1),
                    style = MaterialTheme.typography.headlineMedium,
                    color = accent,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = tapLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Taps on the handoff panel are ignored this long after it appears. */
private const val HANDOFF_ARM_MILLIS = 500L

/** Alpha of the scrim of the handoff panel; the board stays visible through it. */
private const val HANDOFF_SCRIM_ALPHA = 0.55f

/**
 * Hexagon in a player's colour, the mark of the player in a match: filled, or an outline for a player
 * who is not on the move. It is centred on [center] with corners at [radius], by default filling the
 * draw area.
 */
internal fun DrawScope.drawPlayerHex(
    color: Color,
    filled: Boolean,
    center: Offset = this.center,
    radius: Float = size.minDimension * 0.48f,
) {
    val stroke = radius * 0.25f
    val r = if (filled) radius else radius - stroke / 2f
    val path = Path()
    for (k in 0 until 6) {
        val angle = (PI / 6.0 + k * PI / 3.0).toFloat()
        val x = center.x + cos(angle) * r
        val y = center.y + sin(angle) * r
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    if (filled) {
        drawPath(path, color)
    } else {
        drawPath(path, color, style = Stroke(width = stroke, join = StrokeJoin.Round))
    }
}

/**
 * Cells of [player] in a match: the player's hexagon and the count. The player on the move gets a
 * filled hexagon and a full-strength ring in their colour; the other one an outlined hexagon.
 */
@Composable
private fun PlayerPill(vm: GameViewModel, player: Int) {
    val accent = themeColors.players[player]
    val score = vm.matchScore(player)
    val active = !vm.matchOver && vm.matchPlayer == player
    StatPill(
        value = score.toString(),
        contentDescription = stringResource(
            if (active) R.string.match_score_turn_desc else R.string.match_score_desc, player + 1, score,
        ),
        accent = accent,
        modifier = if (active) Modifier.border(2.dp, accent, RoundedCornerShape(50)) else Modifier,
        icon = { drawPlayerHex(it, filled = active) },
    )
}

/**
 * End of a match: the winner in their colour, or a draw, and the cells of both players, with a new
 * match on the same settings and the way back to the menu. [unownedMines] mines nobody found count for
 * nobody; the card names their number when there are any.
 */
@Composable
private fun MatchResultCard(
    leader: Int,
    firstScore: Int,
    secondScore: Int,
    unownedMines: Int,
    compact: Boolean,
    onPlayAgain: () -> Unit,
    onMenu: () -> Unit,
) {
    val colors = themeColors
    val accent = if (leader >= 0) colors.players[leader] else colors.gold
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
            text = if (leader >= 0) {
                stringResource(R.string.match_result_winner, leader + 1)
            } else {
                stringResource(R.string.match_result_draw)
            },
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.match_result_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            for (player in 0 until MatchEngine.PLAYERS) {
                val score = if (player == 0) firstScore else secondScore
                val description = stringResource(R.string.match_score_desc, player + 1, score)
                Column(
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.match_player, player + 1),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.players[player],
                    )
                    Text(
                        text = score.toString(),
                        style = numberTextStyle(24f, colors.textPrimary),
                    )
                }
            }
        }
        if (unownedMines > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.match_result_unowned_mines, unownedMines),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            GlowTextButton(
                text = stringResource(R.string.action_play_again),
                onClick = onPlayAgain,
                modifier = Modifier.weight(if (compact) 1.4f else 1f),
                primary = true,
                accent = colors.primary,
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
 * One feature card over the dimmed game: the heading "On this board", "2/3" when there are several cards,
 * then the legend drawing and text of the feature
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

/** "On this board", with the card counter on the right when there are several cards. */
@Composable
private fun FeatureCardHeader(cards: FeatureCards) {
    val colors = themeColors
    val count = cards.features.size
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.feature_card_board),
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

/**
 * The help line under the ball: the controls, with the far cell tip only on boards that have far cells.
 * A match has its own controls: no automatic flags, and a flag claims a mine.
 */
@Composable
private fun hintText(vm: GameViewModel): String {
    val controls = if (vm.config is GameConfig.Match) {
        stringResource(if (vm.flagByLongPress) R.string.match_hint_long_press else R.string.match_hint_double_tap)
    } else {
        stringResource(if (vm.flagByLongPress) R.string.hint_long_press else R.string.hint_double_tap)
    }
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
            match = vm.match,
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

/**
 * Buttons and counters around the ball. [onExit] and [onRestart] run for the menu and restart buttons;
 * during a match they ask for confirmation first.
 */
@Composable
private fun Hud(vm: GameViewModel, landscape: Boolean, onExit: () -> Unit, onRestart: () -> Unit) {
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
            onClick = onExit,
            contentDescription = menuDescription,
            accent = colors.secondary,
            icon = { drawMenuIcon(it) },
        )
    }
    val restartButton: @Composable () -> Unit = {
        GlowIconButton(
            onClick = onRestart,
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
    // A match shows the cells of both players where a single game shows its mines and time.
    val match = vm.config is GameConfig.Match
    val firstPill: @Composable () -> Unit = {
        if (match) PlayerPill(vm, 0) else minesPill()
    }
    val secondPill: @Composable () -> Unit = {
        if (match) PlayerPill(vm, 1) else timePill()
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
                firstPill()
                if (vm.config.lives > 1) LivesPill(vm.livesLeft)
            }
            Column(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                restartButton()
                secondPill()
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
                firstPill()
                Spacer(Modifier.width(10.dp))
                secondPill()
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
