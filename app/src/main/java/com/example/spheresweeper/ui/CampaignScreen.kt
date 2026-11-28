package com.example.spheresweeper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.spheresweeper.R
import com.example.spheresweeper.logic.Branch
import com.example.spheresweeper.logic.Campaign
import com.example.spheresweeper.logic.CampaignProgress
import com.example.spheresweeper.logic.LevelSpec
import com.example.spheresweeper.ui.theme.UiPalette
import com.example.spheresweeper.ui.theme.numberTextStyle
import com.example.spheresweeper.ui.theme.themeColors
import kotlin.math.roundToInt

/**
 * Campaign map: the trunk as a path of levels, a fork, then one section per branch in [BRANCH_ORDER].
 * Levels are laid out in rows of [PATH_COLUMNS] that snake left to right and back, joined by lines. On
 * entry the map scrolls to the level the player is most likely to play next.
 */
@Composable
fun CampaignScreen(vm: GameViewModel) {
    BackHandler { vm.backToMenu() }

    val progress = vm.progress
    val lastPlayed = (vm.config as? GameConfig.Level)?.spec
    val focus = remember(progress, lastPlayed) { focusLevel(progress, lastPlayed) }
    val scrollState = rememberScrollState()
    val anchor = remember { ScrollAnchor() }
    var autoScrolled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (autoScrolled) return@LaunchedEffect
        // One frame lets the map lay out, so the focused node and the viewport have positions.
        withFrameNanos { }
        val target = anchor.centeredScroll(scrollState.value)
        if (target != null) scrollState.scrollTo(target.coerceIn(0, scrollState.maxValue))
        autoScrolled = true
    }

    val onPlay: (LevelSpec) -> Unit = { vm.startLevel(it) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoColumns = maxWidth >= TWO_COLUMN_MIN_WIDTH
        ThemeBackground()

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            CampaignTopBar(totalStars = progress.totalStars, onBack = { vm.backToMenu() })
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { anchor.viewport = it }
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BranchSection(
                    branch = Branch.TRUNK,
                    progress = progress,
                    focus = focus,
                    anchor = anchor,
                    onPlay = onPlay,
                    modifier = Modifier.widthIn(max = SECTION_MAX_WIDTH),
                )
                ForkConnector(
                    trunkDone = progress.isBranchOpen(Branch.WALLS),
                    modifier = Modifier
                        .widthIn(max = if (twoColumns) SECTION_MAX_WIDTH * 2 else SECTION_MAX_WIDTH)
                        .fillMaxWidth()
                        .height(44.dp),
                )
                if (twoColumns) {
                    for (pair in BRANCH_ORDER.chunked(2)) {
                        Row(
                            modifier = Modifier
                                .widthIn(max = SECTION_MAX_WIDTH * 2 + SECTION_GAP)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(SECTION_GAP),
                        ) {
                            for (branch in pair) {
                                BranchSection(
                                    branch = branch,
                                    progress = progress,
                                    focus = focus,
                                    anchor = anchor,
                                    onPlay = onPlay,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // A lone last section keeps the width of one column.
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(SECTION_GAP))
                    }
                } else {
                    for (branch in BRANCH_ORDER) {
                        BranchSection(
                            branch = branch,
                            progress = progress,
                            focus = focus,
                            anchor = anchor,
                            onPlay = onPlay,
                            modifier = Modifier.widthIn(max = SECTION_MAX_WIDTH),
                        )
                        Spacer(Modifier.height(SECTION_GAP))
                    }
                }
            }
        }
    }
}

/** Accent colour of a branch; the feature branches use the colour the ball draws that feature in. */
internal fun branchAccent(branch: Branch, colors: UiPalette): Color = when (branch) {
    Branch.TRUNK -> colors.primary
    Branch.WALLS -> colors.walls
    Branch.FAR -> colors.gold
    Branch.LINES -> colors.lines
    Branch.MIXED -> colors.secondary
    Branch.PORTALS -> colors.portals
    Branch.LOCKS -> colors.locks
    Branch.ISLANDS -> colors.blue
}

/** Colour of text in a branch colour: the theme's darker text shade, or the accent itself. */
internal fun branchTextColor(branch: Branch, colors: UiPalette): Color {
    val text = colors.branchText ?: return branchAccent(branch, colors)
    return when (branch) {
        Branch.TRUNK -> text.trunk
        Branch.WALLS -> text.walls
        Branch.FAR -> text.far
        Branch.LINES -> text.lines
        Branch.MIXED -> text.mixed
        Branch.PORTALS -> text.portals
        Branch.LOCKS -> text.locks
        Branch.ISLANDS -> text.islands
    }
}

internal fun branchNameRes(branch: Branch): Int = when (branch) {
    Branch.TRUNK -> R.string.branch_trunk
    Branch.WALLS -> R.string.branch_walls
    Branch.FAR -> R.string.branch_far
    Branch.LINES -> R.string.branch_lines
    Branch.MIXED -> R.string.branch_mixed
    Branch.PORTALS -> R.string.branch_portals
    Branch.LOCKS -> R.string.branch_locks
    Branch.ISLANDS -> R.string.branch_islands
}

/** [CampaignProgress.MAX_STARS] stars in a row, the first [stars] filled. Drawing only, no semantics. */
@Composable
internal fun StarRow(
    stars: Int,
    starSize: Dp,
    modifier: Modifier = Modifier,
    emptyColor: Color = themeColors.textMuted,
) {
    val filledColor = themeColors.gold
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(starSize * 0.15f)) {
        for (k in 0 until CampaignProgress.MAX_STARS) {
            val filled = k < stars
            Canvas(Modifier.size(starSize)) {
                drawStarIcon(if (filled) filledColor else emptyColor, filled)
            }
        }
    }
}

/** The first open level of [branch] that is not completed yet, or null. */
private fun frontier(progress: CampaignProgress, branch: Branch): LevelSpec? =
    Campaign.levels(branch).firstOrNull { progress.isUnlocked(it) && !progress.isCompleted(it) }

/**
 * Level the map scrolls to: after a level game the next one to play in that branch (or the played
 * level itself when the branch is done); otherwise the first open, not completed level in the order
 * the map shows them: the trunk, then the branches in [BRANCH_ORDER].
 */
private fun focusLevel(progress: CampaignProgress, lastPlayed: LevelSpec?): LevelSpec? {
    if (lastPlayed != null) return frontier(progress, lastPlayed.branch) ?: lastPlayed
    return frontier(progress, Branch.TRUNK) ?: BRANCH_ORDER.firstNotNullOfOrNull { frontier(progress, it) }
}

/** Layout positions needed to scroll the focused level into the middle of the map. */
private class ScrollAnchor {
    var viewport: LayoutCoordinates? = null
    var target: LayoutCoordinates? = null

    /** Scroll value that centres [target] in [viewport], or null when either is not laid out. */
    fun centeredScroll(currentScroll: Int): Int? {
        val v = viewport ?: return null
        val t = target ?: return null
        if (!v.isAttached || !t.isAttached) return null
        val offsetInContent = t.positionInRoot().y - v.positionInRoot().y + currentScroll
        return (offsetInContent + t.size.height / 2f - v.size.height / 2f).roundToInt()
    }
}

@Composable
private fun CampaignTopBar(totalStars: Int, onBack: () -> Unit) {
    val maxStars = Campaign.all.size * CampaignProgress.MAX_STARS
    val colors = themeColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowIconButton(
            onClick = onBack,
            contentDescription = stringResource(R.string.action_back),
            accent = colors.secondary,
            icon = { drawBackIcon(it) },
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(R.string.campaign_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        Spacer(Modifier.width(8.dp))
        StatPill(
            value = stringResource(R.string.campaign_fraction, totalStars, maxStars),
            contentDescription = stringResource(R.string.campaign_stars_total_desc, totalStars, maxStars),
            accent = colors.gold,
            icon = { drawStarIcon(it) },
        )
    }
}

/** Where the trunk splits: one stem coming down, one drop per branch in its colour, in map order. */
@Composable
private fun ForkConnector(trunkDone: Boolean, modifier: Modifier = Modifier) {
    val colors = themeColors
    val stemColor = if (trunkDone) branchAccent(Branch.TRUNK, colors).copy(alpha = 0.8f) else colors.panelBorder
    val drops = BRANCH_ORDER
    val dropColors = drops.map { branchAccent(it, colors).copy(alpha = if (trunkDone) 0.85f else 0.35f) }
    Canvas(modifier) {
        val stroke = PATH_STROKE.toPx()
        val midY = size.height * 0.45f
        val first = size.width / (drops.size * 2f)
        val last = size.width - first
        drawLine(stemColor, Offset(size.width / 2f, 0f), Offset(size.width / 2f, midY), stroke, StrokeCap.Round)
        drawLine(stemColor, Offset(first, midY), Offset(last, midY), stroke, StrokeCap.Round)
        for (k in drops.indices) {
            val x = first + (last - first) * k / (drops.size - 1)
            val color = dropColors[k]
            drawLine(color, Offset(x, midY), Offset(x, size.height - stroke * 2f), stroke, StrokeCap.Round)
            drawCircle(color, radius = stroke * 1.4f, center = Offset(x, size.height - stroke * 2f))
        }
    }
}

@Composable
private fun BranchSection(
    branch: Branch,
    progress: CampaignProgress,
    focus: LevelSpec?,
    anchor: ScrollAnchor,
    onPlay: (LevelSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors
    val accent = branchAccent(branch, colors)
    val open = progress.isBranchOpen(branch)
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = if (open) 0.12f else 0.05f), colors.panel.copy(alpha = 0.72f)),
                ),
            )
            .border(1.dp, accent.copy(alpha = if (open) 0.45f else 0.18f), shape)
            .padding(horizontal = 10.dp, vertical = 14.dp),
    ) {
        BranchHeader(branch, progress, open)
        Spacer(Modifier.height(8.dp))
        LevelPath(branch, progress, accent, focus, anchor, onPlay)
    }
}

@Composable
private fun BranchHeader(branch: Branch, progress: CampaignProgress, open: Boolean) {
    val colors = themeColors
    val accent = branchAccent(branch, colors)
    val name = stringResource(branchNameRes(branch))
    val size = Campaign.levels(branch).size
    val completed = progress.completedCount(branch)
    val description = if (open) {
        stringResource(R.string.campaign_branch_progress_desc, name, completed, size)
    } else {
        stringResource(R.string.campaign_branch_locked_desc, name)
    }
    Column(Modifier.padding(horizontal = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    heading()
                    contentDescription = description
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (open) 1f else 0.4f)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = if (open) colors.textPrimary else colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (!open) {
                val lockColor = colors.textMuted
                Canvas(Modifier.size(16.dp)) { drawLockIcon(lockColor) }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = stringResource(R.string.campaign_fraction, completed, size),
                style = numberTextStyle(15f, if (completed == size) colors.gold else colors.textSecondary),
            )
        }
        if (!open) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = unlockCondition(branch),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            val feeders = feederProgress(branch, progress)
            if (feeders.isNotEmpty()) {
                Text(
                    text = feeders,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun unlockCondition(branch: Branch): String = when (branch) {
    Branch.TRUNK -> ""
    Branch.WALLS, Branch.FAR, Branch.LINES, Branch.PORTALS, Branch.LOCKS ->
        stringResource(R.string.campaign_unlock_after_trunk)
    Branch.MIXED -> {
        val feeder = CampaignProgress.MIXED_FEEDERS.first()
        stringResource(
            R.string.campaign_unlock_mixed,
            CampaignProgress.mixedUnlockCount(feeder),
            Campaign.levels(feeder).size,
        )
    }
    Branch.ISLANDS -> stringResource(
        R.string.campaign_unlock_islands,
        CampaignProgress.islandsUnlockCount(),
        Campaign.levels(CampaignProgress.ISLANDS_FEEDER).size,
    )
}

/**
 * Progress towards opening [branch] when other branches open it, for example
 * "Walls 4/10 · Far reach 2/10 · Lines 0/10" for the mixed branch. Empty for the other branches.
 */
@Composable
private fun feederProgress(branch: Branch, progress: CampaignProgress): String {
    val feeders: List<Pair<Branch, Int>> = when (branch) {
        Branch.MIXED -> CampaignProgress.MIXED_FEEDERS.map { it to CampaignProgress.mixedUnlockCount(it) }
        Branch.ISLANDS -> listOf(CampaignProgress.ISLANDS_FEEDER to CampaignProgress.islandsUnlockCount())
        Branch.TRUNK, Branch.WALLS, Branch.FAR, Branch.LINES, Branch.PORTALS, Branch.LOCKS -> emptyList()
    }
    val parts = feeders.map { (feeder, needed) ->
        stringResource(
            R.string.campaign_feeder_progress,
            stringResource(branchNameRes(feeder)),
            minOf(progress.completedCount(feeder), needed),
            needed,
        )
    }
    return parts.joinToString(" · ")
}

/** Index in the branch of the level shown in [row], [column] of the snaking path. */
private fun pathIndex(row: Int, column: Int): Int =
    row * PATH_COLUMNS + if (row % 2 == 0) column else PATH_COLUMNS - 1 - column

/** Centre of the node of level [index], for a path [width] pixels wide. */
private fun pathCenter(index: Int, width: Float, rowHeight: Float, nodeCenterY: Float): Offset {
    val row = index / PATH_COLUMNS
    val position = index % PATH_COLUMNS
    val column = if (row % 2 == 0) position else PATH_COLUMNS - 1 - position
    val slot = width / PATH_COLUMNS
    return Offset(slot * (column + 0.5f), row * rowHeight + nodeCenterY)
}

@Composable
private fun LevelPath(
    branch: Branch,
    progress: CampaignProgress,
    accent: Color,
    focus: LevelSpec?,
    anchor: ScrollAnchor,
    onPlay: (LevelSpec) -> Unit,
) {
    val levels = Campaign.levels(branch)
    val rows = (levels.size + PATH_COLUMNS - 1) / PATH_COLUMNS
    val current = frontier(progress, branch)
    val doneColor = accent.copy(alpha = 0.75f)
    val todoColor = themeColors.panelBorder.copy(alpha = 0.55f)
    Box(Modifier.fillMaxWidth()) {
        // Lines between consecutive levels; a line is lit once the level it starts from is completed.
        Canvas(Modifier.matchParentSize()) {
            val rowHeight = PATH_ROW_HEIGHT.toPx()
            val nodeCenterY = (NODE_TOP + NODE_SIZE / 2).toPx()
            val stroke = PATH_STROKE.toPx()
            for (i in 0 until levels.size - 1) {
                drawLine(
                    color = if (progress.isCompleted(levels[i])) doneColor else todoColor,
                    start = pathCenter(i, size.width, rowHeight, nodeCenterY),
                    end = pathCenter(i + 1, size.width, rowHeight, nodeCenterY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
        Column(Modifier.fillMaxWidth()) {
            for (row in 0 until rows) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(PATH_ROW_HEIGHT),
                ) {
                    for (column in 0 until PATH_COLUMNS) {
                        val index = pathIndex(row, column)
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            if (index < levels.size) {
                                val level = levels[index]
                                val anchorModifier = if (level == focus) {
                                    Modifier.onGloballyPositioned { anchor.target = it }
                                } else {
                                    Modifier
                                }
                                LevelNode(
                                    level = level,
                                    accent = accent,
                                    stars = progress.stars(level),
                                    unlocked = progress.isUnlocked(level),
                                    current = level == current,
                                    onClick = { onPlay(level) },
                                    modifier = anchorModifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One level: a numbered circle with its best stars below. A locked level is dimmed and shows a padlock
 * instead of its number; the next level to play in a branch pulses with a glow.
 */
@Composable
private fun LevelNode(
    level: LevelSpec,
    accent: Color,
    stars: Int,
    unlocked: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = stringResource(branchNameRes(level.branch))
    val state = when {
        !unlocked -> stringResource(R.string.campaign_node_locked)
        stars > 0 -> stringResource(R.string.campaign_node_stars, stars, CampaignProgress.MAX_STARS)
        else -> stringResource(R.string.campaign_node_not_completed)
    }
    val description = stringResource(R.string.campaign_node_desc, name, level.number, state)
    val completed = stars > 0
    val colors = themeColors
    Column(
        modifier = modifier
            .padding(top = NODE_TOP - NODE_TOUCH_PADDING)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = unlocked, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = NODE_TOUCH_PADDING, vertical = NODE_TOUCH_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(NODE_SIZE), contentAlignment = Alignment.Center) {
            if (current) PulsingGlow(accent, Modifier.matchParentSize())
            Canvas(Modifier.matchParentSize()) {
                val r = size.minDimension / 2f
                val border = 2.dp.toPx()
                // Opaque base so the path lines do not show through the node.
                drawCircle(colors.backgroundMid, radius = r)
                when {
                    !unlocked -> {
                        drawCircle(colors.panel, radius = r)
                        drawCircle(colors.panelBorder.copy(alpha = 0.6f), radius = r - border / 2f, style = Stroke(width = border / 2f))
                    }
                    completed -> {
                        val fill = colors.completedFill
                        drawCircle(
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = fill), accent.copy(alpha = fill * COMPLETED_EDGE_RATIO)),
                                center = Offset(center.x - r * 0.3f, center.y - r * 0.3f),
                                radius = r * 1.6f,
                            ),
                            radius = r,
                        )
                        drawCircle(accent, radius = r - border / 2f, style = Stroke(width = border))
                    }
                    else -> {
                        drawCircle(accent.copy(alpha = 0.14f), radius = r)
                        drawCircle(accent, radius = r - border / 2f, style = Stroke(width = border))
                    }
                }
            }
            if (unlocked) {
                Text(
                    text = level.number.toString(),
                    style = numberTextStyle(
                        17f,
                        if (completed) colors.textPrimary else branchTextColor(level.branch, colors),
                    ),
                    // The node's description already names the level.
                    modifier = Modifier.clearAndSetSemantics { },
                )
            } else {
                Canvas(Modifier.size(18.dp)) { drawLockIcon(colors.textMuted) }
            }
        }
        Spacer(Modifier.height(NODE_STAR_GAP))
        if (unlocked) {
            StarRow(stars = stars, starSize = NODE_STAR_SIZE, emptyColor = colors.textMuted.copy(alpha = 0.6f))
        } else {
            Spacer(Modifier.height(NODE_STAR_SIZE))
        }
    }
}

/** Soft halo around the current level that slowly breathes in and out. */
@Composable
private fun PulsingGlow(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "levelGlow")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1100), repeatMode = RepeatMode.Reverse),
        label = "levelGlowPulse",
    )
    Canvas(modifier) {
        // Reading the pulse only here keeps the animation in the draw phase.
        val r = size.minDimension / 2f
        val spread = GLOW_SPREAD.toPx() * (0.55f + 0.45f * pulse)
        for (step in 1..3) {
            drawCircle(color, radius = r + spread * step / 3f, alpha = (0.32f - 0.1f * pulse) / step)
        }
    }
}

/**
 * Branches below the fork, in map order: first the five that open after the trunk, then the two that
 * open after progress in other branches. Mixed follows the walls, far and lines branches it builds
 * on, and islands come last because they open only after the portals branch.
 */
private val BRANCH_ORDER = listOf(
    Branch.WALLS, Branch.FAR, Branch.LINES, Branch.PORTALS, Branch.LOCKS, Branch.MIXED, Branch.ISLANDS,
)

private const val PATH_COLUMNS = 5
private val PATH_ROW_HEIGHT = 84.dp
private val PATH_STROKE = 3.dp
private val NODE_SIZE = 46.dp
private val NODE_TOP = 8.dp
private val NODE_TOUCH_PADDING = 4.dp
private val NODE_STAR_GAP = 5.dp
private val NODE_STAR_SIZE = 11.dp
private val GLOW_SPREAD = 12.dp

/** Alpha of a completed level's fill at its edge relative to its alpha at the highlight. */
private const val COMPLETED_EDGE_RATIO = 0.22f / 0.7f
private val SECTION_MAX_WIDTH = 520.dp
private val SECTION_GAP = 16.dp

/** From this width the branches sit in two columns. */
private val TWO_COLUMN_MIN_WIDTH = 760.dp
