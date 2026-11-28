package com.example.spheresweeper.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.spheresweeper.R
import com.example.spheresweeper.logic.BoardSize
import com.example.spheresweeper.logic.Branch
import com.example.spheresweeper.logic.GameStats
import com.example.spheresweeper.logic.StatsTotals
import com.example.spheresweeper.ui.theme.numberTextStyle
import com.example.spheresweeper.ui.theme.themeColors
import kotlin.math.roundToInt

/**
 * Statistics of finished games: a free-play section with one card per board size and a campaign
 * section with one card per branch (the trunk is a card of its own). Only sizes and branches with at
 * least one finished game get a card; a section without any shows a short note, and with no games at
 * all the screen shows a single empty-state panel. Back, including the system back, calls [onBack].
 */
@Composable
fun StatsScreen(stats: GameStats, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize()) {
        ThemeBackground()
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            StatsTopBar(onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                ) {
                    if (stats.isEmpty) {
                        EmptyPanel()
                    } else {
                        FreeSection(stats)
                        Spacer(Modifier.height(20.dp))
                        CampaignSection(stats)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun StatsTopBar(onBack: () -> Unit) {
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
            text = stringResource(R.string.stats_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
    }
}

/** Shown instead of both sections before the first finished game. */
@Composable
private fun EmptyPanel() {
    val shape = RoundedCornerShape(18.dp)
    val colors = themeColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panel.copy(alpha = CARD_PANEL_ALPHA))
            .border(1.dp, colors.primary.copy(alpha = 0.45f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(R.string.stats_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.stats_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun ColumnScope.FreeSection(stats: GameStats) {
    SectionTitle(stringResource(R.string.menu_free_play))
    val colors = themeColors
    val sizes = BoardSize.values()
    val played = sizes.indices.filter { stats.totals(sizes[it]).played > 0 }
    if (played.isEmpty()) SectionEmptyNote()
    for (index in played) {
        val size = sizes[index]
        TotalsCard(
            name = stringResource(boardNameRes(size)),
            accent = boardAccent(index, sizes.size, colors),
            totals = stats.totals(size),
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ColumnScope.CampaignSection(stats: GameStats) {
    SectionTitle(stringResource(R.string.campaign_title))
    val colors = themeColors
    val branches = Branch.values().filter { stats.totals(it).played > 0 }
    if (branches.isEmpty()) SectionEmptyNote()
    for (branch in branches) {
        TotalsCard(
            name = stringResource(branchNameRes(branch)),
            accent = branchAccent(branch, colors),
            totals = stats.totals(branch),
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.titleSmall,
        color = themeColors.textMuted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 10.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SectionEmptyNote() {
    Text(
        text = stringResource(R.string.stats_section_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = themeColors.textMuted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 10.dp),
    )
}

/**
 * Totals of one board size or branch, sized for a phone width: the name with games and wins and the
 * win rate on the right, then best time, average winning time, current and best streak in a
 * two-by-two grid. [accent] tints the card the way the menu tints its board cards; the text stays in
 * the theme's text colours.
 */
@Composable
private fun TotalsCard(name: String, accent: Color, totals: StatsTotals) {
    val shape = RoundedCornerShape(18.dp)
    val colors = themeColors
    val none = stringResource(R.string.stats_no_value)
    val percent = ((totals.winRate ?: 0.0) * 100).roundToInt()
    val rateDescription = stringResource(R.string.stats_win_rate_desc, percent)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panel.copy(alpha = CARD_PANEL_ALPHA))
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0f))))
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Text(
                    text = stringResource(R.string.stats_games_wins, totals.played, totals.wins),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.stats_win_rate, percent),
                style = numberTextStyle(20f, colors.gold),
                modifier = Modifier.semantics { contentDescription = rateDescription },
            )
        }
        Spacer(Modifier.height(10.dp))
        StatRow(
            R.string.best_label, totals.bestTimeMillis?.let { formatDuration(it) } ?: none,
            R.string.stats_average, totals.averageWinMillis?.let { formatDuration(it) } ?: none,
        )
        Spacer(Modifier.height(8.dp))
        StatRow(
            R.string.stats_streak, totals.currentStreak.toString(),
            R.string.stats_best_streak, totals.bestStreak.toString(),
        )
    }
}

/** Two labelled values side by side, each taking half of the card width. */
@Composable
private fun StatRow(@StringRes leftLabel: Int, leftValue: String, @StringRes rightLabel: Int, rightValue: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCell(stringResource(leftLabel), leftValue, Modifier.weight(1f))
        StatCell(stringResource(rightLabel), rightValue, Modifier.weight(1f))
    }
}

/** One labelled value of a [TotalsCard]; read out as one item by screen readers. */
@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = themeColors
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        Text(text = value, style = numberTextStyle(16f, colors.textPrimary))
    }
}
