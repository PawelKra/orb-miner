package com.example.spheresweeper.logic

import kotlin.math.roundToInt

/**
 * Parts of the campaign tree. [TRUNK] is the classic introduction; the other branches grow out of
 * it, each built around one board feature: walls, far cells, line cells ([MIXED] combines those
 * three), portals, locks and islands joined by portals. [LINES], [MIXED] and [LOCKS] split the board
 * into rooms joined by passages, where their line cells and locks stand.
 */
enum class Branch { TRUNK, WALLS, FAR, LINES, MIXED, PORTALS, LOCKS, ISLANDS }

/**
 * One fixed campaign level. The meaning of the board fields matches [BoardSize]; [index] counts from 0
 * within [branch] and [id] (for example "walls-07") uses the 1-based [number]. [seed] fixes the ocean,
 * walls, portals, far cells, line cells, locks, mines and the start cell, so the level is the same on
 * every play.
 *
 * [islands] is the island count of [BoardLayout.generate] (0: no ocean), [portals] the extra portal
 * pairs on top of those joining the islands, [locks] the lock count of [NoGuessGenerator.generate] and
 * [rooms] the room count of [BoardLayout.generate] (0: no rooms). With rooms, [lineCells] and [locks]
 * are upper limits: line cells and locks stand only at passages between rooms, so a built level
 * usually has fewer of them.
 *
 * [mines] is the nominal mine count. Without ocean the built board has exactly that many. With
 * islands the land size is drawn, so [Campaign.buildLevel] puts the branch's mine density on the
 * real land instead ([Campaign.mineCount]); the board then has [PreparedLevel.mines].size mines,
 * which [MinesweeperBoard.mineCount] also reports, and [mines] is only the count for the expected land.
 */
data class LevelSpec(
    val id: String,
    val branch: Branch,
    val index: Int,
    val frequency: Int,
    val mines: Int,
    val lives: Int,
    val hints: Int,
    val walls: Int,
    val wallMinLength: Int,
    val wallMaxLength: Int,
    val farCells: Int,
    val lineCells: Int,
    val seed: Long,
    val islands: Int = 0,
    val portals: Int = 0,
    val locks: Int = 0,
    val rooms: Int = 0,
) {
    /** 1-based position in the branch, as shown to the player. */
    val number: Int get() = index + 1

    val cellCount: Int get() = 10 * frequency * frequency + 2

    /**
     * Cells in play: all of them without ocean, otherwise the expected land ([Campaign.expectedLand]).
     * The real land of a built level differs by a few cells, since the ocean size is drawn.
     */
    val playCells: Int get() = if (islands > 0) Campaign.expectedLand(cellCount) else cellCount

    /** Share of [playCells] holding a mine, nominal like [mines]. */
    val mineDensity: Double get() = mines.toDouble() / playCells
}

/** A campaign level ready to play: [board] already has its mines and [start] revealed. */
class PreparedLevel(
    val spec: LevelSpec,
    val layout: BoardLayout,
    /** Cell revealed at the start; it shows 0. */
    val start: Int,
    /** Mine cells, sorted ascending; their count is [Campaign.mineCount] of the level. */
    val mines: IntArray,
    val board: MinesweeperBoard,
    /** Mine layouts [NoGuessGenerator] drew from scratch after the first one ([NoGuessLayout.redraws]). */
    val redraws: Int = 0,
)

/**
 * Piecewise linear curve through evenly spaced control points: [at] 0 gives the first point, [at] 1
 * the last, and a middle point (if any) sits at 0.5. Two points make a straight ramp.
 */
class Ramp(vararg val points: Double) {
    init {
        require(points.isNotEmpty()) { "a ramp needs at least one point" }
    }

    fun at(t: Double): Double {
        if (points.size == 1) return points[0]
        val x = t.coerceIn(0.0, 1.0) * (points.size - 1)
        val k = minOf(x.toInt(), points.size - 2)
        return points[k] + (points[k + 1] - points[k]) * (x - k)
    }

    companion object {
        val ZERO = Ramp(0.0)
    }
}

/**
 * How the levels of one branch grow. Every value is read from its [Ramp] at t = index / (size - 1)
 * and rounded to the nearest integer; [density] is the share of cells in play holding a mine
 * ([LevelSpec.playCells]: the expected land when the level has islands).
 */
class BranchTuning(
    val prefix: String,
    val size: Int,
    val frequency: Ramp,
    val density: Ramp,
    val lives: Ramp,
    val hints: Ramp,
    val walls: Ramp = Ramp.ZERO,
    val wallMaxLength: Ramp = Ramp.ZERO,
    val farCells: Ramp = Ramp.ZERO,
    val lineCells: Ramp = Ramp.ZERO,
    val islands: Ramp = Ramp.ZERO,
    val portals: Ramp = Ramp.ZERO,
    val locks: Ramp = Ramp.ZERO,
    val rooms: Ramp = Ramp.ZERO,
)

/**
 * The campaign: a trunk of classic levels and seven branches of fixed levels, all derived from
 * [TUNING]. Levels never change between runs: parameters come from the table and every level's
 * seed comes from its id ([seedFor]).
 */
object Campaign {
    /**
     * The single place to tune the campaign. Each ramp lists the first and last value of a branch;
     * add middle points to bend a curve.
     *
     * - Trunk: frequency 2 to 4 (42 to 162 cells), mine density 11% to 15%. It teaches the rules on
     *   boards smaller and sparser than [BoardSize.MEDIUM] (17%) and has no walls, far or line cells.
     * - Walls, far, lines: frequency 3 to 8 (92 to 642 cells), density 14% to 19.5%. A branch opens
     *   on a small board so the new feature is easy to read, and ends near [BoardSize.GIANT] (19.5%).
     *   The feature count grows with the board; walls and far cells go a little beyond [BoardSize] at
     *   the top because the branch is about that feature.
     * - Lines: 3 to 9 rooms and up to 2 to 8 line cells. Every line cell stands at a passage and
     *   looks into the next room, at most one per passage, so a level has at most rooms - 1 of them;
     *   the room count keeps that number growing along the branch.
     * - Mixed: opens only after half of the other three branches, so it starts denser (15%) and ends
     *   at 20%. It uses all three features, each with fewer elements than its own branch so that the
     *   board stays readable: 1 to 5 walls, 2 to 12 far cells, 3 to 6 rooms and up to 2 to 5 line
     *   cells.
     *
     * - Portals, locks: like the walls branch, frequency 3 to 8 and density 14% to 19.5%, with 1 to 4
     *   extra portal pairs or up to 1 to 6 locks. No ocean, far or line cells and no walls other than
     *   those between rooms, so the one new feature is easy to follow. The locks branch splits the
     *   board into 2 to 7 rooms: each lock stands at a passage into the next room and shows its number
     *   once its own room is solved. Only some passages can hold a lock, so a level often gets fewer
     *   locks than its count.
     * - Islands: 2 to 4 islands joined by portals, plus 0 to 2 extra portal pairs. Two islands need
     *   frequency 4 and three need 5 ([BoardLayout.maxIslandCount]), so the branch runs from
     *   frequency 4 to 8. Density 14% to 19.5% counts the land of the built board, not the ocean
     *   ([mineCount]).
     *
     * Built levels of the lines and mixed branches have at least one line cell and those of the locks
     * branch at least one lock, and along a branch the count actually placed never falls by more than
     * two from one level to the next. CampaignRoomsTest checks both on the pinned boards; a retuning
     * that breaks them needs other room counts for the affected levels.
     *
     * Lives fall from 3 to 1 and hints from 2 to 0 within every branch: early levels forgive mistakes
     * while the player learns, the last ones play by classic rules. Boards come from
     * [NoGuessGenerator], so a careful player never needs either; stars reward not using them.
     */
    val TUNING: Map<Branch, BranchTuning> = mapOf(
        Branch.TRUNK to BranchTuning(
            prefix = "trunk", size = 10,
            frequency = Ramp(2.0, 4.0), density = Ramp(0.11, 0.15),
            lives = Ramp(3.0, 1.0), hints = Ramp(2.0, 0.0),
        ),
        Branch.WALLS to BranchTuning(
            prefix = "walls", size = 20,
            frequency = Ramp(3.0, 8.0), density = Ramp(0.14, 0.195),
            lives = Ramp(3.0, 1.0), hints = Ramp(2.0, 0.0),
            walls = Ramp(2.0, 8.0), wallMaxLength = Ramp(4.0, 14.0),
        ),
        Branch.FAR to BranchTuning(
            prefix = "far", size = 20,
            frequency = Ramp(3.0, 8.0), density = Ramp(0.14, 0.195),
            lives = Ramp(3.0, 1.0), hints = Ramp(2.0, 0.0),
            farCells = Ramp(3.0, 20.0),
        ),
        Branch.LINES to BranchTuning(
            prefix = "lines", size = 20,
            frequency = Ramp(3.0, 8.0), density = Ramp(0.14, 0.195),
            lives = Ramp(3.0, 1.0), hints = Ramp(2.0, 0.0),
            lineCells = Ramp(2.0, 8.0), rooms = Ramp(3.0, 9.0),
        ),
        Branch.MIXED to BranchTuning(
            prefix = "mixed", size = 20,
            frequency = Ramp(3.0, 8.0), density = Ramp(0.15, 0.20),
            lives = Ramp(3.0, 1.0), hints = Ramp(2.0, 0.0),
            walls = Ramp(1.0, 5.0), wallMaxLength = Ramp(4.0, 12.0),
            farCells = Ramp(2.0, 12.0), lineCells = Ramp(2.0, 5.0), rooms = Ramp(3.0, 6.0),
        ),
        Branch.PORTALS to BranchTuning(
            prefix = "portals", size = 20,
            frequency = Ramp(3.0, 8.0), density = Ramp(0.14, 0.195),
            lives = Ramp(3.0, 1.0), hints = Ramp(2.0, 0.0),
            portals = Ramp(1.0, 4.0),
        ),
        Branch.LOCKS to BranchTuning(
            prefix = "locks", size = 20,
            frequency = Ramp(3.0, 8.0), density = Ramp(0.14, 0.195),
            lives = Ramp(3.0, 1.0), hints = Ramp(2.0, 0.0),
            locks = Ramp(1.0, 6.0), rooms = Ramp(2.0, 7.0),
        ),
        Branch.ISLANDS to BranchTuning(
            prefix = "islands", size = 20,
            frequency = Ramp(4.0, 8.0), density = Ramp(0.14, 0.195),
            lives = Ramp(3.0, 1.0), hints = Ramp(2.0, 0.0),
            islands = Ramp(2.0, 4.0), portals = Ramp(0.0, 2.0),
        ),
    )

    private val byBranch: Map<Branch, List<LevelSpec>> =
        Branch.values().associateWith { buildBranch(it, TUNING.getValue(it)) }

    /** Every level: the trunk first, then the branches in [Branch] order, each in play order. */
    val all: List<LevelSpec> = Branch.values().flatMap { byBranch.getValue(it) }

    private val byIdMap: Map<String, LevelSpec> = all.associateBy { it.id }

    fun levels(branch: Branch): List<LevelSpec> = byBranch.getValue(branch)

    fun byId(id: String): LevelSpec? = byIdMap[id]

    /** The level after [level] in its branch, or null for the last one. */
    fun next(level: LevelSpec): LevelSpec? = levels(level.branch).getOrNull(level.index + 1)

    /**
     * Expected land on a board of [cellCount] cells with islands: the ocean takes on average the middle
     * of [BoardLayout.MIN_OCEAN_SHARE]..[BoardLayout.MAX_OCEAN_SHARE], rounded to whole cells.
     */
    fun expectedLand(cellCount: Int): Int =
        cellCount - (cellCount * (BoardLayout.MIN_OCEAN_SHARE + BoardLayout.MAX_OCEAN_SHARE) / 2).roundToInt()

    /**
     * Mine density [TUNING] sets for [spec]: its branch's density ramp at the level's position. Levels
     * without ocean round it to [LevelSpec.mines] on the whole sphere when the campaign is built.
     */
    fun nominalDensity(spec: LevelSpec): Double {
        val tuning = TUNING.getValue(spec.branch)
        return tuning.density.at(position(spec.index, tuning.size))
    }

    /**
     * Mines of [spec] built on [layout]: [LevelSpec.mines] without ocean, otherwise [nominalDensity]
     * of the real land ([minesOnLand]). The ocean takes a drawn share of the sphere, so counting the
     * land keeps the density on it rising along the islands branch like on the other branches.
     */
    fun mineCount(spec: LevelSpec, layout: BoardLayout): Int =
        if (spec.islands == 0) spec.mines else minesOnLand(nominalDensity(spec), layout, spec.locks)

    /**
     * Builds [spec] on [geometry] (which must have the level's frequency). The ocean, rooms, walls,
     * portals, far cells, line cells, locks, the mines and the start cell all come from one
     * [StableRandom] seeded with spec.seed, so every call returns the same board on every Kotlin
     * version, as long as [BoardLayout.generate] and [NoGuessGenerator.generate] draw the same way.
     * With no islands, rooms, portals or locks they draw nothing for these features.
     * CampaignGoldenTest pins the result. The mine count ([mineCount]) is fixed after the layout,
     * without drawing. The board is started at once with [MinesweeperBoard.startWith].
     */
    fun buildLevel(spec: LevelSpec, geometry: SphereGeometry): PreparedLevel {
        require(geometry.frequency == spec.frequency) {
            "level ${spec.id} needs frequency ${spec.frequency}, got ${geometry.frequency}"
        }
        val random = StableRandom(spec.seed)
        val layout = BoardLayout.generate(
            geometry, spec.walls, spec.wallMinLength, spec.wallMaxLength, spec.farCells, random, spec.lineCells,
            spec.islands, spec.portals, spec.rooms,
        )
        val mines = mineCount(spec, layout)
        val generated = NoGuessGenerator.generate(layout, mines, random, spec.locks)
        val board = MinesweeperBoard(
            layout.scopes, mines, lives = spec.lives,
            present = layout.presentMask(), lockThresholds = generated.lockThresholds,
            lockRegions = generated.lockRegions,
        )
        board.startWith(generated.mines, generated.start)
        return PreparedLevel(spec, layout, generated.start, generated.mines, board, generated.redraws)
    }

    /**
     * Seed of the level with [id]: 64-bit FNV-1a over its characters. It depends on the id only, so
     * retuning or adding levels leaves the seeds of the others as they are.
     */
    fun seedFor(id: String): Long {
        var hash = FNV_OFFSET
        for (c in id) {
            hash = hash xor c.code.toLong()
            hash *= FNV_PRIME
        }
        return hash
    }

    private val FNV_OFFSET: Long get() = 0xcbf29ce484222325uL.toLong()

    private const val FNV_PRIME: Long = 0x100000001b3L

    /** Ramp position of the level at [index] in a branch of [size] levels: 0 for the first, 1 for the last. */
    private fun position(index: Int, size: Int): Double = if (size > 1) index.toDouble() / (size - 1) else 0.0

    private fun buildBranch(branch: Branch, tuning: BranchTuning): List<LevelSpec> {
        val result = ArrayList<LevelSpec>(tuning.size)
        var previousMines = 0
        var previousCells = 1
        for (index in 0 until tuning.size) {
            val t = position(index, tuning.size)
            val frequency = tuning.frequency.at(t).roundToInt()
            val islands = tuning.islands.at(t).roundToInt()
            val allCells = 10 * frequency * frequency + 2
            val cells = if (islands > 0) expectedLand(allCells) else allCells
            // Rounding to whole mines may dip the density below the previous level's; never let it.
            val mines = maxOf(
                (tuning.density.at(t) * cells).roundToInt(),
                ((previousMines.toLong() * cells + previousCells - 1) / previousCells).toInt(),
            )
            val wallMaxLength = tuning.wallMaxLength.at(t).roundToInt()
            val id = "${tuning.prefix}-${(index + 1).toString().padStart(2, '0')}"
            result.add(
                LevelSpec(
                    id = id,
                    branch = branch,
                    index = index,
                    frequency = frequency,
                    mines = mines,
                    lives = tuning.lives.at(t).roundToInt(),
                    hints = tuning.hints.at(t).roundToInt(),
                    walls = tuning.walls.at(t).roundToInt(),
                    wallMinLength = if (wallMaxLength > 0) maxOf(2, wallMaxLength / 2) else 0,
                    wallMaxLength = wallMaxLength,
                    farCells = tuning.farCells.at(t).roundToInt(),
                    lineCells = tuning.lineCells.at(t).roundToInt(),
                    seed = seedFor(id),
                    islands = islands,
                    portals = tuning.portals.at(t).roundToInt(),
                    locks = tuning.locks.at(t).roundToInt(),
                    rooms = tuning.rooms.at(t).roundToInt(),
                ),
            )
            previousMines = mines
            previousCells = cells
        }
        return result
    }
}
