package com.example.spheresweeper.logic

import kotlin.math.roundToInt

/**
 * Parts of the campaign tree. [TRUNK] is the classic introduction; the other four branches grow
 * out of it, each built around one board feature ([MIXED] combines all three).
 */
enum class Branch { TRUNK, WALLS, FAR, LINES, MIXED }

/**
 * One fixed campaign level. The meaning of the board fields matches [BoardSize]; [index] counts from 0
 * within [branch] and [id] (for example "walls-07") uses the 1-based [number]. [seed] fixes walls, far
 * cells, line cells, mines and the start cell, so the level is the same on every play.
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
) {
    /** 1-based position in the branch, as shown to the player. */
    val number: Int get() = index + 1

    val cellCount: Int get() = 10 * frequency * frequency + 2

    val mineDensity: Double get() = mines.toDouble() / cellCount
}

/** A campaign level ready to play: [board] already has its mines and [start] revealed. */
class PreparedLevel(
    val spec: LevelSpec,
    val layout: BoardLayout,
    /** Cell revealed at the start; it shows 0. */
    val start: Int,
    /** Mine cells, sorted ascending. */
    val mines: IntArray,
    val board: MinesweeperBoard,
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
 * and rounded to the nearest integer; [density] is the share of cells holding a mine.
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
)

/**
 * The campaign: a trunk of classic levels and four branches of fixed levels, all derived from
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
     *   The feature count grows with the board, a little beyond [BoardSize] at the top because the
     *   branch is about that feature.
     * - Mixed: opens only after half of the other three branches, so it starts denser (15%) and ends
     *   at 20%. It uses all three features, each with fewer elements than its own branch so that the
     *   board stays readable.
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
            lineCells = Ramp(2.0, 14.0),
        ),
        Branch.MIXED to BranchTuning(
            prefix = "mixed", size = 20,
            frequency = Ramp(3.0, 8.0), density = Ramp(0.15, 0.20),
            lives = Ramp(3.0, 1.0), hints = Ramp(2.0, 0.0),
            walls = Ramp(1.0, 5.0), wallMaxLength = Ramp(4.0, 12.0),
            farCells = Ramp(2.0, 12.0), lineCells = Ramp(2.0, 9.0),
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
     * Builds [spec] on [geometry] (which must have the level's frequency). Walls, far cells, line
     * cells, the mines and the start cell all come from one [StableRandom] seeded with spec.seed, so
     * every call returns the same board on every Kotlin version, as long as [BoardLayout.generate] and
     * [NoGuessGenerator.generate] draw the same way. CampaignGoldenTest pins the result. The board is
     * started at once with [MinesweeperBoard.startWith].
     */
    fun buildLevel(spec: LevelSpec, geometry: SphereGeometry): PreparedLevel {
        require(geometry.frequency == spec.frequency) {
            "level ${spec.id} needs frequency ${spec.frequency}, got ${geometry.frequency}"
        }
        val random = StableRandom(spec.seed)
        val layout = BoardLayout.generate(
            geometry, spec.walls, spec.wallMinLength, spec.wallMaxLength, spec.farCells, random, spec.lineCells,
        )
        val generated = NoGuessGenerator.generate(layout.scopes, spec.mines, random)
        val board = MinesweeperBoard(layout.scopes, spec.mines, lives = spec.lives)
        board.startWith(generated.mines, generated.start)
        return PreparedLevel(spec, layout, generated.start, generated.mines, board)
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

    private fun buildBranch(branch: Branch, tuning: BranchTuning): List<LevelSpec> {
        val result = ArrayList<LevelSpec>(tuning.size)
        var previousMines = 0
        var previousCells = 1
        for (index in 0 until tuning.size) {
            val t = if (tuning.size > 1) index.toDouble() / (tuning.size - 1) else 0.0
            val frequency = tuning.frequency.at(t).roundToInt()
            val cells = 10 * frequency * frequency + 2
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
                ),
            )
            previousMines = mines
            previousCells = cells
        }
        return result
    }
}
