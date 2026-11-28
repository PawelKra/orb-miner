package com.example.spheresweeper.logic

import kotlin.math.roundToInt

/**
 * Available board sizes. This is the single place to tune difficulty.
 *
 * - [frequency]: icosahedron subdivision frequency n; the board then has 10 * n^2 + 2 cells.
 * - [mines]: number of mines on the whole sphere. It sets the mine density ([mineDensity]); a board
 *   with ocean gets that density of its real land instead ([minesFor]).
 * - [lives]: how many mines the player may hit; the last one ends the game, so 1 means classic rules.
 * - [hints]: how many times the player may ask for a safe cell.
 * - [walls]: number of walls; a number does not count mines on the other side of a wall.
 * - [wallMaxLength]: longest wall in cell edges; the shortest one is half of that, at least 2.
 * - [farCells]: cells whose number counts mines up to two steps away instead of one.
 * - [lineCells]: cells whose number counts mines in a straight row of cells running from one of
 *   their edges, up to 2 * [frequency] cells long, instead of their neighbours. On a board with
 *   rooms they stand only at passages, at most one per passage, so the board often gets fewer.
 * - [islands]: islands the land is split into by the ocean, 0 for a sphere without ocean. At most
 *   [BoardLayout.maxIslandCount] for [frequency]; portals always join the islands.
 * - [portals]: extra portal pairs on top of those that join the islands.
 * - [locks]: cells whose number stays hidden until enough cells are revealed. On a board with rooms
 *   they stand at passages into the next room and show their number once their own room is solved;
 *   only some passages qualify, so the board often gets fewer locks.
 * - [rooms]: rooms the land is split into, passed as the room count of [BoardLayout.generate]; 0
 *   leaves the land open. Rooms are separated by walls and joined by single-edge passages. Every
 *   size stays within [BoardLayout.maxRoomCount] for its [frequency] and [islands]. The counts give
 *   enough passages for about half of [lineCells] or more on average, while a room keeps over 30
 *   cells on average.
 */
enum class BoardSize(
    val frequency: Int,
    val mines: Int,
    val lives: Int,
    val hints: Int,
    val walls: Int,
    val wallMaxLength: Int,
    val farCells: Int,
    val lineCells: Int,
    val islands: Int,
    val portals: Int,
    val locks: Int,
    val rooms: Int,
) {
    TINY(
        frequency = 2, mines = 6, lives = 1, hints = 0, walls = 0, wallMaxLength = 0, farCells = 0, lineCells = 0,
        islands = 0, portals = 0, locks = 0, rooms = 0,
    ),
    SMALL(
        frequency = 3, mines = 14, lives = 1, hints = 1, walls = 2, wallMaxLength = 4, farCells = 3, lineCells = 2,
        islands = 0, portals = 0, locks = 0, rooms = 3,
    ),
    MEDIUM(
        frequency = 4, mines = 28, lives = 2, hints = 1, walls = 3, wallMaxLength = 6, farCells = 5, lineCells = 4,
        islands = 0, portals = 0, locks = 0, rooms = 4,
    ),
    LARGE(
        frequency = 5, mines = 45, lives = 3, hints = 2, walls = 4, wallMaxLength = 8, farCells = 8, lineCells = 6,
        islands = 0, portals = 1, locks = 2, rooms = 6,
    ),
    HUGE(
        frequency = 6, mines = 65, lives = 4, hints = 2, walls = 5, wallMaxLength = 10, farCells = 11, lineCells = 8,
        islands = 2, portals = 1, locks = 3, rooms = 8,
    ),
    GIANT(
        frequency = 8, mines = 125, lives = 5, hints = 3, walls = 7, wallMaxLength = 14, farCells = 18, lineCells = 12,
        islands = 3, portals = 2, locks = 5, rooms = 11,
    );

    val cellCount: Int get() = 10 * frequency * frequency + 2

    val wallMinLength: Int get() = maxOf(2, wallMaxLength / 2)

    /** Share of the cells in play holding a mine: [mines] over all cells of the sphere. */
    val mineDensity: Double get() = mines.toDouble() / cellCount

    /**
     * Mines on a board of this size built on [layout]: [mineDensity] of its land, rounded (see
     * [minesOnLand]). Exactly [mines] when [layout] has no ocean.
     */
    fun minesFor(layout: BoardLayout): Int = minesOnLand(mineDensity, layout, locks)

    /**
     * Mines expected before the board exists: [mines] without islands, otherwise [mineDensity] of the
     * expected land ([Campaign.expectedLand]). The real count depends on the drawn ocean.
     */
    val expectedMines: Int
        get() = if (islands == 0) mines else (mineDensity * Campaign.expectedLand(cellCount)).roundToInt()
}

/**
 * Mines for a board built on [layout] at [density]: that share of its land ([BoardLayout.presentCount])
 * rounded to whole mines, so the ocean never makes the land denser or sparser than intended. Kept
 * within 0..[NoGuessGenerator.maxMineCount] for [lockCount] locks, so [NoGuessGenerator.generate]
 * accepts it. On a board without ocean, [density] = m / cellCount gives back exactly m.
 */
fun minesOnLand(density: Double, layout: BoardLayout, lockCount: Int = 0): Int =
    (density * layout.presentCount).roundToInt().coerceIn(0, NoGuessGenerator.maxMineCount(layout, lockCount))
