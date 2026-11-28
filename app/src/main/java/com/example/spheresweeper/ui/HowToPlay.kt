package com.example.spheresweeper.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.spheresweeper.R
import com.example.spheresweeper.logic.BoardFeature
import com.example.spheresweeper.math.MathUtils
import com.example.spheresweeper.ui.theme.LocalGameTheme
import com.example.spheresweeper.ui.theme.SpherePalette
import com.example.spheresweeper.ui.theme.themeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import android.graphics.Canvas as NativeCanvas

/**
 * "How to play": the goal of the game, then a legend of the cell types. Each legend entry shows a
 * small flat patch of cells drawn in the current theme's ball colours, with the same marks the ball
 * shows ([CellDecals]), next to a title and a short explanation. Back returns to the menu.
 */
@Composable
fun HowToPlayScreen(vm: GameViewModel) {
    BackHandler { vm.backToMenu() }

    val painter = rememberLegendPainter()

    Box(Modifier.fillMaxSize()) {
        ThemeBackground()
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            HowToTopBar(onBack = { vm.backToMenu() })
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
                    GoalPanel()
                    for (entry in LEGEND) {
                        Spacer(Modifier.height(10.dp))
                        LegendItem(entry, painter)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** Painter of legend drawings in the current theme's ball colours and font. */
@Composable
private fun rememberLegendPainter(): LegendPainter {
    val theme = LocalGameTheme.current
    val context = LocalContext.current
    return remember(theme) { LegendPainter(theme.sphere, theme.font.canvasTypeface(context)) }
}

/**
 * The legend drawing of [feature], as the "How to play" screen shows it, across the full width of
 * [modifier]. It is as tall as the drawing with cells of [LARGE_CELL_RADIUS_DP] inscribed radius, but
 * at most [maxHeight]; a narrower or lower space shrinks the cells. Like the legend, it has no semantics
 * of its own: [FeatureText] describes it.
 */
@Composable
fun FeatureDrawing(feature: BoardFeature, modifier: Modifier = Modifier, maxHeight: Dp = Dp.Infinity) {
    val painter = rememberLegendPainter()
    val scene = legendEntry(feature).scene
    val naturalHeight = ((scene.maxY - scene.minY) * LARGE_CELL_RADIUS_DP / (1f - 2f * PADDING)).dp
    Canvas(
        modifier
            .fillMaxWidth()
            .height(naturalHeight.coerceAtMost(maxHeight)),
    ) {
        drawIntoCanvas { painter.draw(it.nativeCanvas, scene, size.width, size.height) }
    }
}

/** Title and explanation of the legend entry of [feature], for use inside a Column. */
@Composable
fun FeatureText(feature: BoardFeature) {
    LegendText(legendEntry(feature))
}

@Composable
private fun HowToTopBar(onBack: () -> Unit) {
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
            text = stringResource(R.string.how_to_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
    }
}

/** The goal of the game and the no-guessing promise, above the legend. */
@Composable
private fun GoalPanel() {
    val shape = RoundedCornerShape(18.dp)
    val colors = themeColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panel.copy(alpha = 0.7f))
            .border(1.dp, colors.primary.copy(alpha = 0.45f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(R.string.how_to_goal_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.how_to_goal),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

/**
 * One legend entry: the drawing on the left, title and explanation on the right. A [LegendEntry.large]
 * entry puts a full-width drawing above the text instead, with cells of [LARGE_CELL_RADIUS_DP].
 */
@Composable
private fun LegendItem(entry: LegendEntry, painter: LegendPainter) {
    val shape = RoundedCornerShape(18.dp)
    val colors = themeColors
    val cardModifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(colors.panel.copy(alpha = 0.7f))
        .border(1.dp, colors.panelBorder.copy(alpha = 0.7f), shape)
        .padding(horizontal = 14.dp, vertical = 12.dp)
    // The drawing only illustrates the text next to it, so it has no semantics of its own.
    if (entry.large) {
        val scene = entry.scene
        // Height at which the scene fits with cells of LARGE_CELL_RADIUS_DP inside the padding.
        val drawingHeight = ((scene.maxY - scene.minY) * LARGE_CELL_RADIUS_DP / (1f - 2f * PADDING)).dp
        Column(cardModifier) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(drawingHeight),
            ) {
                drawIntoCanvas { painter.draw(it.nativeCanvas, scene, size.width, size.height) }
            }
            Spacer(Modifier.height(8.dp))
            LegendText(entry)
        }
    } else {
        Row(cardModifier, verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = SMALL_DRAWING_WIDTH_DP.dp, height = SMALL_DRAWING_HEIGHT_DP.dp)) {
                drawIntoCanvas { painter.draw(it.nativeCanvas, entry.scene, size.width, size.height) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { LegendText(entry) }
        }
    }
}

/** Title and explanation of a legend entry. */
@Composable
private fun LegendText(entry: LegendEntry) {
    val colors = themeColors
    Text(
        text = stringResource(entry.titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = colors.textPrimary,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = stringResource(entry.textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textSecondary,
    )
}

// Grid geometry. Declared before the scenes below, which read it while they are built.

private val SQRT3 = sqrt(3f)

/** Circumradius of a hexagon in units of its inscribed radius. */
private val CIRCUM_RADIUS = 2f / SQRT3

private val DEG_TO_RAD = (PI / 180.0).toFloat()

/** Corner k of a cell, relative to its centre, in units of the inscribed radius (y down). */
private val CORNER_X = FloatArray(6) { CIRCUM_RADIUS * cos(it * 60f * DEG_TO_RAD) }
private val CORNER_Y = FloatArray(6) { CIRCUM_RADIUS * sin(it * 60f * DEG_TO_RAD) }

/** Direction of the outward normal of edge k in degrees (y down, clockwise from +x). */
private val EDGE_ANGLE = FloatArray(6) { it * 60f + 30f }

/** Grid step to the neighbour across edge k. */
private val EDGE_COL = intArrayOf(1, 0, -1, -1, 0, 1)
private val EDGE_Y = intArrayOf(1, 2, 1, -1, -2, -1)

// ---------------------------------------------------------------------- legend data

/**
 * Cell positions use a flat-topped hexagon grid in units of the inscribed radius: a cell in column
 * `col` at height `y` has its centre at (sqrt(3) * col, y), and y has the parity of col. Edge k of a
 * cell runs from corner k to corner k + 1, corner k lying at 60k degrees (y down); its neighbour across
 * that edge is at [EDGE_COL] and [EDGE_Y]: 0 lower right, 1 down, 2 lower left, 3 upper left, 4 up,
 * 5 upper right.
 */
private enum class CellFill { COVERED, REVEALED, MINE, OCEAN }

/** A mark drawn on a legend cell by [CellDecals]. */
private sealed class CellMark {
    class Digit(val n: Int) : CellMark()
    object Flag : CellMark()
    class Far(val n: Int) : CellMark()

    /** Line number [n] with its arrow pointing through [edge]. */
    class Line(val n: Int, val edge: Int) : CellMark()

    /** Portal mark of [pair]; [full] is the ring with a dot of a covered cell. */
    class Portal(val pair: Int, val full: Boolean) : CellMark()

    /** Closed padlock with [count] cells left to reveal. */
    class Lock(val count: Int) : CellMark()

    /** Small open padlock below the number of an opened lock. */
    object OpenLock : CellMark()
}

private class LegendCell(val col: Int, val y: Int, val fill: CellFill, val marks: Array<out CellMark>)

/** Wall along edge [edge] of the cell at ([col], [y]). */
private class WallEdge(val col: Int, val y: Int, val edge: Int)

/** Row of a line cell at ([col], [y]): [length] cells straight through [edge]. */
private class LineRow(val col: Int, val y: Int, val edge: Int, val length: Int)

/** Dotted arc between the two cells of a portal pair, in the colour of [pair]. */
private class PortalLink(val colA: Int, val yA: Int, val colB: Int, val yB: Int, val pair: Int)

private class LegendScene(
    val cells: List<LegendCell>,
    val walls: List<WallEdge> = emptyList(),
    val rows: List<LineRow> = emptyList(),
    val links: List<PortalLink> = emptyList(),
) {
    // Bounds of all cell polygons in grid units.
    val minX: Float = cells.minOf { SQRT3 * it.col } - CIRCUM_RADIUS
    val maxX: Float = cells.maxOf { SQRT3 * it.col } + CIRCUM_RADIUS
    val minY: Float = cells.minOf { it.y } - 1f
    val maxY: Float = cells.maxOf { it.y } + 1f
}

/** [large]: the drawing spans the card above the text, so that small numbers on it stay readable. */
private class LegendEntry(
    @StringRes val titleRes: Int,
    @StringRes val textRes: Int,
    val scene: LegendScene,
    val large: Boolean = false,
)

private fun cell(col: Int, y: Int, fill: CellFill, vararg marks: CellMark) = LegendCell(col, y, fill, marks)

private val COVERED = CellFill.COVERED
private val REVEALED = CellFill.REVEALED
private val MINE = CellFill.MINE
private val OCEAN = CellFill.OCEAN

/** Two mines touch the 2. */
private val NUMBER_SCENE = LegendScene(
    cells = listOf(
        cell(0, 0, REVEALED, CellMark.Digit(2)),
        cell(0, -2, MINE),
        cell(1, 1, MINE),
        cell(1, -1, COVERED),
        cell(0, 2, COVERED),
        cell(-1, 1, COVERED),
        cell(-1, -1, COVERED),
    ),
)

/** A flagged cell next to the 1 that gave it away. */
private val FLAG_SCENE = LegendScene(
    cells = listOf(
        cell(-1, -1, REVEALED, CellMark.Digit(1)),
        cell(0, 0, COVERED, CellMark.Flag),
        cell(0, -2, COVERED),
        cell(1, -1, COVERED),
    ),
)

/** The 1 counts the mine above it; the wall hides the mine at its upper right. */
private val WALL_SCENE = LegendScene(
    cells = listOf(
        cell(0, 0, REVEALED, CellMark.Digit(1)),
        cell(0, -2, MINE),
        cell(1, -1, MINE),
        cell(1, 1, COVERED),
        cell(0, 2, COVERED),
        cell(-1, 1, COVERED),
        cell(-1, -1, COVERED),
    ),
    walls = listOf(WallEdge(0, -2, 0), WallEdge(0, 0, 5), WallEdge(1, 1, 4)),
)

/**
 * Wall between two rooms, two columns each, with its zigzag running from the top to the bottom of the
 * patch; edge 5 of the cell at (1, 3) is left open as the passage.
 */
private val ROOM_WALLS = listOf(
    WallEdge(2, 0, 3),
    WallEdge(1, 1, 5),
    WallEdge(1, 1, 0),
    WallEdge(1, 3, 0),
    WallEdge(1, 5, 5),
    WallEdge(1, 5, 0),
)

/** The left room is open; its zeros reached the right room only through the passage. */
private val PASSAGE_SCENE = LegendScene(
    cells = listOf(
        cell(0, 0, REVEALED),
        cell(0, 2, REVEALED),
        cell(0, 4, REVEALED),
        cell(1, 1, REVEALED),
        cell(1, 3, REVEALED),
        cell(1, 5, REVEALED),
        cell(2, 0, COVERED),
        cell(2, 2, REVEALED, CellMark.Digit(1)),
        cell(2, 4, COVERED),
        cell(3, 1, COVERED),
        cell(3, 3, COVERED),
        cell(3, 5, COVERED),
    ),
    walls = ROOM_WALLS,
)

/** A far cell sees all 18 cells within two steps: one mine next to it and two on the outer ring. */
private val FAR_SCENE = LegendScene(
    cells = buildList<LegendCell> {
        for (q in -2..2) {
            for (r in -2..2) {
                if (q + r !in -2..2) continue
                val col = q
                val y = 2 * r + q
                val fill = when {
                    col == 0 && y == 0 -> REVEALED
                    (col == 0 && y == -2) || (col == 2 && y == 0) || (col == -2 && y == 0) -> MINE
                    else -> COVERED
                }
                if (fill == REVEALED) add(cell(col, y, fill, CellMark.Far(3))) else add(cell(col, y, fill))
            }
        }
    },
)

/**
 * A line cell at the passage, in the left room: its arrow points through the gap in the wall and its
 * row runs three cells deep into the right room. The 3 counts the three flagged mines in that row; its
 * neighbours hold only two flags, (0, 2) in its own room and (2, 2) across the passage.
 */
private val LINE_SCENE = LegendScene(
    cells = listOf(
        cell(0, 0, COVERED),
        cell(0, 2, COVERED, CellMark.Flag),
        cell(1, 1, REVEALED, CellMark.Digit(1)),
        cell(1, 3, REVEALED, CellMark.Line(3, 5)),
        cell(2, 0, COVERED),
        cell(2, 2, COVERED, CellMark.Flag),
        cell(3, 1, COVERED, CellMark.Flag),
        cell(3, 3, COVERED),
        cell(4, 0, COVERED, CellMark.Flag),
        cell(4, 2, COVERED),
    ),
    // Wall between columns 1 and 2 from the top to the bottom of the patch; edge 5 of (1, 3) is the passage.
    walls = listOf(WallEdge(2, 0, 3), WallEdge(1, 1, 5), WallEdge(1, 1, 0), WallEdge(1, 3, 0)),
    rows = listOf(LineRow(1, 3, 5, 3)),
)

/** Two distant cells of one portal pair: a covered one with the full mark, a revealed one with a number. */
private val PORTAL_SCENE = LegendScene(
    cells = listOf(
        cell(0, 0, COVERED, CellMark.Portal(0, full = true)),
        cell(0, -2, COVERED),
        cell(0, 2, COVERED),
        cell(3, -1, REVEALED, CellMark.Digit(1), CellMark.Portal(0, full = false)),
        cell(3, -3, COVERED),
        cell(3, 1, COVERED),
    ),
    links = listOf(PortalLink(0, 0, 3, -1, 0)),
)

/**
 * The padlock sits in the left room at its exit, on the cell next to the passage. Its count is the
 * three safe cells of the left room still covered, the padlock cell itself included; the fourth covered
 * cell there is the flagged mine both 1s see. The wall keeps the right room out of the scope of the 1
 * at (2, 0). Apart on the right, an opened lock shows its number with the small open padlock.
 */
private val LOCK_SCENE = LegendScene(
    cells = listOf(
        cell(0, 0, REVEALED, CellMark.Digit(1)),
        cell(0, 2, COVERED),
        cell(1, 1, COVERED, CellMark.Flag),
        cell(1, 3, COVERED),
        cell(2, 0, REVEALED, CellMark.Digit(1)),
        cell(2, 2, COVERED, CellMark.Lock(3)),
        cell(3, 1, COVERED),
        cell(3, 3, COVERED),
        cell(5, 1, REVEALED, CellMark.Digit(2), CellMark.OpenLock),
    ),
    // Wall between columns 2 and 3 from the top to the bottom of the patch; edge 5 of (2, 2) is the passage.
    walls = listOf(WallEdge(2, 0, 5), WallEdge(2, 0, 0), WallEdge(2, 2, 0), WallEdge(3, 3, 2)),
)

/** Two islands in the ocean, joined by a portal pair. */
private val ISLAND_SCENE = LegendScene(
    cells = buildList<LegendCell> {
        val land = setOf(0 to 0, 0 to 2, 1 to 1, 1 to 3, 3 to 3, 4 to 2, 4 to 4)
        for (col in 0..4) {
            var y = col % 2
            while (y <= 5) {
                val key = col to y
                when {
                    key == (1 to 1) || key == (4 to 2) -> add(cell(col, y, COVERED, CellMark.Portal(0, full = true)))
                    key in land -> add(cell(col, y, COVERED))
                    else -> add(cell(col, y, OCEAN))
                }
                y += 2
            }
        }
    },
    links = listOf(PortalLink(1, 1, 4, 2, 0)),
)

private val NUMBER_ENTRY = LegendEntry(R.string.how_to_number_title, R.string.how_to_number, NUMBER_SCENE)
private val FLAG_ENTRY = LegendEntry(R.string.how_to_flag_title, R.string.how_to_flag, FLAG_SCENE)
private val WALL_ENTRY = LegendEntry(R.string.how_to_wall_title, R.string.how_to_wall, WALL_SCENE)
private val PASSAGE_ENTRY = LegendEntry(R.string.how_to_passage_title, R.string.how_to_passage, PASSAGE_SCENE)
private val FAR_ENTRY = LegendEntry(R.string.how_to_far_title, R.string.how_to_far, FAR_SCENE)
private val LINE_ENTRY = LegendEntry(R.string.how_to_line_title, R.string.how_to_line, LINE_SCENE, large = true)
private val PORTAL_ENTRY = LegendEntry(R.string.how_to_portal_title, R.string.how_to_portal, PORTAL_SCENE)
private val LOCK_ENTRY = LegendEntry(R.string.how_to_lock_title, R.string.how_to_lock, LOCK_SCENE, large = true)
private val ISLAND_ENTRY = LegendEntry(R.string.how_to_island_title, R.string.how_to_island, ISLAND_SCENE)

private val LEGEND = listOf(
    NUMBER_ENTRY,
    FLAG_ENTRY,
    WALL_ENTRY,
    PASSAGE_ENTRY,
    FAR_ENTRY,
    LINE_ENTRY,
    PORTAL_ENTRY,
    LOCK_ENTRY,
    ISLAND_ENTRY,
)

/** Legend entry that explains [feature]; numbers and flags are basics and belong to no feature. */
private fun legendEntry(feature: BoardFeature): LegendEntry = when (feature) {
    BoardFeature.WALLS -> WALL_ENTRY
    BoardFeature.ROOMS -> PASSAGE_ENTRY
    BoardFeature.FAR -> FAR_ENTRY
    BoardFeature.LINES -> LINE_ENTRY
    BoardFeature.PORTALS -> PORTAL_ENTRY
    BoardFeature.LOCKS -> LOCK_ENTRY
    BoardFeature.ISLANDS -> ISLAND_ENTRY
}

// ---------------------------------------------------------------------- drawing

/**
 * Draws legend scenes flat, in the colours of [palette]: cell fills as a cell facing the viewer gets
 * them on the ball, the cell border, walls with their glow, line rows and the marks of [CellDecals].
 * Paints, the path and the colours are built once per theme; [draw] only reuses them.
 */
private class LegendPainter(private val palette: SpherePalette, typeface: Typeface) {

    private val decals = CellDecals(palette, typeface, MAX_LOCK_COUNT)
    private val path = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = palette.border
    }

    /** Outline in the ocean colour, so neighbouring ocean cells merge without seams. */
    private val oceanEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val wallGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = MathUtils.withAlpha(palette.wallGlow, WALL_GLOW_ALPHA)
    }
    private val wallCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = palette.wallCore
    }
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = MathUtils.withAlpha(palette.line, ROW_ALPHA)
    }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Light on a cell that faces the viewer, from the ball's lighting model. */
    private val light = palette.lighting.ambient + palette.lighting.diffuse * FRONT_LAMBERT

    /** Covered and revealed fills in a few shades around the middle of the covered colours. */
    private val coveredColors = IntArray(SHADES) { coveredColor(shadeT(it)) }
    private val revealedColors = IntArray(SHADES) { revealedColor(shadeT(it)) }
    private val oceanColor = oceanColor()
    private val mineCellColor = mineCellColor()

    fun draw(canvas: NativeCanvas, scene: LegendScene, width: Float, height: Float) {
        val pad = min(width, height) * PADDING
        val sceneWidth = scene.maxX - scene.minX
        val sceneHeight = scene.maxY - scene.minY
        val s = min((width - 2f * pad) / sceneWidth, (height - 2f * pad) / sceneHeight)
        if (s <= 0f) return
        val ox = width * 0.5f - (scene.minX + scene.maxX) * 0.5f * s
        val oy = height * 0.5f - (scene.minY + scene.maxY) * 0.5f * s
        val circumPx = CIRCUM_RADIUS * s

        // Ocean first, so that land borders lie on top of the ocean's merged edges.
        oceanEdgePaint.strokeWidth = borderWidth(circumPx)
        val cells = scene.cells
        for (i in 0 until cells.size) {
            val cell = cells[i]
            if (cell.fill != CellFill.OCEAN) continue
            buildHexPath(ox + SQRT3 * cell.col * s, oy + cell.y * s, s)
            fillPaint.color = oceanColor
            canvas.drawPath(path, fillPaint)
            oceanEdgePaint.color = oceanColor
            canvas.drawPath(path, oceanEdgePaint)
        }
        borderPaint.strokeWidth = borderWidth(circumPx)
        for (i in 0 until cells.size) {
            val cell = cells[i]
            val color = when (cell.fill) {
                CellFill.OCEAN -> continue
                CellFill.COVERED -> coveredColors[shadeIndex(cell)]
                CellFill.REVEALED -> revealedColors[shadeIndex(cell)]
                CellFill.MINE -> mineCellColor
            }
            buildHexPath(ox + SQRT3 * cell.col * s, oy + cell.y * s, s)
            fillPaint.color = color
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, borderPaint)
        }

        val links = scene.links
        for (i in 0 until links.size) drawLink(canvas, links[i], ox, oy, s)

        // Rows lie under the marks, so flags and numbers on the row stay whole.
        rowPaint.strokeWidth = ROW_WIDTH_PER_RADIUS * circumPx
        val rows = scene.rows
        for (i in 0 until rows.size) drawRow(canvas, rows[i], ox, oy, s)

        for (i in 0 until cells.size) {
            val cell = cells[i]
            val cx = ox + SQRT3 * cell.col * s
            val cy = oy + cell.y * s
            if (cell.fill == CellFill.MINE) {
                onDecal(canvas, cx, cy, s) { decals.drawMine(canvas, palette.mine, 1f) }
            }
            val marks = cell.marks
            for (k in 0 until marks.size) drawMark(canvas, marks[k], cx, cy, s)
        }

        val wallWidth = WALL_WIDTH_PER_RADIUS * circumPx
        wallGlowPaint.strokeWidth = wallWidth * WALL_GLOW_FACTOR
        wallCorePaint.strokeWidth = wallWidth
        val walls = scene.walls
        for (i in 0 until walls.size) drawWallEdge(canvas, wallGlowPaint, walls[i], ox, oy, s)
        for (i in 0 until walls.size) drawWallEdge(canvas, wallCorePaint, walls[i], ox, oy, s)
    }

    private fun drawMark(canvas: NativeCanvas, mark: CellMark, cx: Float, cy: Float, s: Float) {
        when (mark) {
            is CellMark.Digit -> onDecal(canvas, cx, cy, s) { decals.drawDigit(canvas, mark.n, 1f) }
            is CellMark.Flag -> onDecal(canvas, cx, cy, s) { decals.drawFlag(canvas, palette.pole, palette.flag, 1f) }
            is CellMark.Far -> onDecal(canvas, cx, cy, s) { decals.drawFar(canvas, mark.n, 1f) }
            is CellMark.Line -> onDecal(canvas, cx, cy, s) {
                decals.drawLineNumber(canvas, mark.n, 1f)
                // The arrow points towards -y in decal space; turn it onto the edge's outward normal.
                canvas.rotate(EDGE_ANGLE[mark.edge] - 270f)
                decals.drawLineArrow(canvas, 1f)
            }
            is CellMark.Portal -> onDecal(canvas, cx, cy, s) { decals.drawPortal(canvas, mark.pair, mark.full, 1f) }
            is CellMark.Lock -> onDecal(canvas, cx, cy, s) { decals.drawLock(canvas, mark.count, 1f, 1f) }
            is CellMark.OpenLock -> onDecal(canvas, cx, cy, s) { decals.drawOpenLock(canvas, 1f) }
        }
    }

    /**
     * Saves the canvas, maps decal space onto the cell centred at ([cx], [cy]) whose inscribed radius is
     * [s] pixels, runs [paint] and restores the canvas.
     */
    private inline fun onDecal(canvas: NativeCanvas, cx: Float, cy: Float, s: Float, paint: () -> Unit) {
        canvas.save()
        canvas.translate(cx, cy)
        val k = s / CellDecals.HALF_SIZE
        canvas.scale(k, k)
        paint()
        canvas.restore()
    }

    /** Faint line from the arrow edge of a line cell through the centres of its row, as on the ball. */
    private fun drawRow(canvas: NativeCanvas, row: LineRow, ox: Float, oy: Float, s: Float) {
        val angle = EDGE_ANGLE[row.edge] * DEG_TO_RAD
        val cx = ox + SQRT3 * row.col * s
        val cy = oy + row.y * s
        val startX = cx + cos(angle) * s
        val startY = cy + sin(angle) * s
        val endX = ox + SQRT3 * (row.col + EDGE_COL[row.edge] * row.length) * s
        val endY = oy + (row.y + EDGE_Y[row.edge] * row.length) * s
        canvas.drawLine(startX, startY, endX, endY, rowPaint)
    }

    /** One wall edge in [paint]: the glow pass and the core pass use the same geometry. */
    private fun drawWallEdge(canvas: NativeCanvas, paint: Paint, wall: WallEdge, ox: Float, oy: Float, s: Float) {
        val cx = ox + SQRT3 * wall.col * s
        val cy = oy + wall.y * s
        val a = wall.edge
        val b = (wall.edge + 1) % 6
        canvas.drawLine(
            cx + CORNER_X[a] * s, cy + CORNER_Y[a] * s,
            cx + CORNER_X[b] * s, cy + CORNER_Y[b] * s,
            paint,
        )
    }

    /**
     * Dotted arc in the pair's colour between the two cells of a portal pair, bulging upwards; it
     * leaves the cell centres free for their marks.
     */
    private fun drawLink(canvas: NativeCanvas, link: PortalLink, ox: Float, oy: Float, s: Float) {
        val ax = ox + SQRT3 * link.colA * s
        val ay = oy + link.yA * s
        val bx = ox + SQRT3 * link.colB * s
        val by = oy + link.yB * s
        val dx = bx - ax
        val dy = by - ay
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0f) return
        // Control point above the middle of the chord, on the side of the perpendicular that points up.
        var nx = dy / length
        var ny = -dx / length
        if (ny > 0f) {
            nx = -nx
            ny = -ny
        }
        val bulge = length * LINK_BULGE
        val qx = (ax + bx) * 0.5f + nx * bulge
        val qy = (ay + by) * 0.5f + ny * bulge
        val colors = palette.portals
        linkPaint.color = MathUtils.withAlpha(colors[link.pair % colors.size], LINK_ALPHA)
        val dots = max(2, (length / (LINK_DOT_SPACING * s)).toInt())
        val radius = LINK_DOT_RADIUS * s
        for (k in 0..dots) {
            val t = LINK_END_GAP + (1f - 2f * LINK_END_GAP) * k / dots
            val u = 1f - t
            val x = u * u * ax + 2f * u * t * qx + t * t * bx
            val y = u * u * ay + 2f * u * t * qy + t * t * by
            canvas.drawCircle(x, y, radius, linkPaint)
        }
    }

    /** Hexagon of a cell centred at ([cx], [cy]) with inscribed radius [s] pixels, into [path]. */
    private fun buildHexPath(cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx + CORNER_X[0] * s, cy + CORNER_Y[0] * s)
        for (k in 1 until 6) path.lineTo(cx + CORNER_X[k] * s, cy + CORNER_Y[k] * s)
        path.close()
    }

    /** Cell border in pixels for a cell of circumradius [circumPx] pixels, with the ball's limits. */
    private fun borderWidth(circumPx: Float): Float = min(BORDER_MAX, max(BORDER_MIN, circumPx * BORDER_PER_RADIUS))

    /** Fixed shade per cell position, so neighbouring cells differ slightly as on the ball. */
    private fun shadeIndex(cell: LegendCell): Int = (cell.col * 7 + cell.y * 13).mod(SHADES)

    private fun shadeT(index: Int): Float = MathUtils.clamp01(0.5f + (index - SHADES / 2) * SHADE_STEP)

    /** Covered colour at [t] along the palette's south-to-north colours, red channel first. */
    private fun coveredBase(t: Float, channel: Int): Float {
        val stops = palette.covered
        val scaled = t * (stops.size - 1)
        val k = min(stops.size - 2, scaled.toInt())
        val u = scaled - k
        val a = stops[k]
        val b = stops[k + 1]
        return when (channel) {
            0 -> MathUtils.lerp(a.r, b.r, u)
            1 -> MathUtils.lerp(a.g, b.g, u)
            else -> MathUtils.lerp(a.b, b.b, u)
        }
    }

    private fun coveredColor(t: Float): Int {
        val l = palette.lighting
        val shade = l.coveredFlat + l.coveredLit * light
        return MathUtils.argb(1f, coveredBase(t, 0) * shade, coveredBase(t, 1) * shade, coveredBase(t, 2) * shade)
    }

    /** Revealed colour: the palette's own, or the covered colour desaturated, shaded like the ball. */
    private fun revealedColor(t: Float): Int {
        val l = palette.lighting
        val shade = l.revealedFlat + l.revealedLit * light
        val lift = l.revealedLift
        val fixed = palette.revealed
        val r: Float
        val g: Float
        val b: Float
        if (fixed != null) {
            r = fixed.r
            g = fixed.g
            b = fixed.b
        } else {
            val cr = coveredBase(t, 0)
            val cg = coveredBase(t, 1)
            val cb = coveredBase(t, 2)
            val gray = (cr + cg + cb) / 3f
            val d = palette.revealedDesaturation
            r = MathUtils.lerp(cr, gray, d)
            g = MathUtils.lerp(cg, gray, d)
            b = MathUtils.lerp(cb, gray, d)
        }
        return MathUtils.argb(1f, r * shade + lift.r, g * shade + lift.g, b * shade + lift.b)
    }

    /** Ocean halfway between its south and north colours, shaded like the ball. */
    private fun oceanColor(): Int {
        val l = palette.lighting
        val shade = l.oceanFlat + l.oceanLit * light
        val south = palette.oceanSouth
        val north = palette.oceanNorth
        return MathUtils.argb(
            1f,
            MathUtils.lerp(south.r, north.r, 0.5f) * shade,
            MathUtils.lerp(south.g, north.g, 0.5f) * shade,
            MathUtils.lerp(south.b, north.b, 0.5f) * shade,
        )
    }

    /** Fill of a mine shown in a drawing: the colour the ball gives the mines after a loss. */
    private fun mineCellColor(): Int {
        val l = palette.lighting
        val shade = l.coveredFlat + l.coveredLit * light
        val c = palette.lostMineCell
        return MathUtils.argb(1f, c.r * shade, c.g * shade, c.b * shade)
    }
}

/** Largest padlock count the drawings show. */
private const val MAX_LOCK_COUNT = 9

/**
 * Lambert term of a cell facing the viewer: the z component of the ball's light direction
 * (-0.45, 0.6, 0.66) after normalising.
 */
private const val FRONT_LAMBERT = 0.66f

private const val SHADES = 5
private const val SHADE_STEP = 0.04f

/** Empty margin around a drawing, as a share of its smaller side; leaves room for the wall glow. */
private const val PADDING = 0.04f

/**
 * Drawing beside the text, in dp. On a 360 dp wide screen the card keeps about 170 dp for the text, and
 * the far scene gets cells of about 8.8 dp inscribed radius, so its number stays about 8 dp tall.
 */
private const val SMALL_DRAWING_WIDTH_DP = 108f
private const val SMALL_DRAWING_HEIGHT_DP = 96f

/**
 * Inscribed cell radius of a large drawing in dp. A padlock count is 0.38 of it tall, about 8.4 dp;
 * the widest large scene (the padlock, 11 grid units) then needs about 251 dp, which a 360 dp screen has.
 */
private const val LARGE_CELL_RADIUS_DP = 22f

// Cell border and walls with the ball renderer's proportions, relative to the circumradius.
private const val BORDER_MIN = 1f
private const val BORDER_MAX = 2.6f
private const val BORDER_PER_RADIUS = 0.035f
private const val WALL_WIDTH_PER_RADIUS = 0.07f
private const val WALL_GLOW_FACTOR = 3.2f
private const val WALL_GLOW_ALPHA = 0.4f

// Line rows bolder and more opaque than on the ball (0.045, 0.4), so they read on the small flat drawings:
// about 3 dp wide at LARGE_CELL_RADIUS_DP.
private const val ROW_WIDTH_PER_RADIUS = 0.12f
private const val ROW_ALPHA = 0.6f

// Portal link: dot spacing and radius in units of the inscribed radius.
private const val LINK_BULGE = 0.3f
private const val LINK_DOT_SPACING = 0.45f
private const val LINK_DOT_RADIUS = 0.09f
private const val LINK_ALPHA = 0.85f

/** Share of the arc left free at each end, so the dots stay off the marks of the two cells. */
private const val LINK_END_GAP = 0.14f
