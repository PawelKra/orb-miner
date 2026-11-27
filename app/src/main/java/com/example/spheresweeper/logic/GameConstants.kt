package com.example.spheresweeper.logic

/**
 * Available board sizes. This is the single place to tune difficulty.
 *
 * - [frequency]: icosahedron subdivision frequency n; the board then has 10 * n^2 + 2 cells.
 * - [mines]: number of mines.
 * - [lives]: how many mines the player may hit; the last one ends the game, so 1 means classic rules.
 * - [hints]: how many times the player may ask for a safe cell.
 * - [walls]: number of walls; a number does not count mines on the other side of a wall.
 * - [wallMaxLength]: longest wall in cell edges; the shortest one is half of that, at least 2.
 * - [farCells]: cells whose number counts mines up to two steps away instead of one.
 */
enum class BoardSize(
    val frequency: Int,
    val mines: Int,
    val lives: Int,
    val hints: Int,
    val walls: Int,
    val wallMaxLength: Int,
    val farCells: Int,
) {
    TINY(frequency = 2, mines = 6, lives = 1, hints = 0, walls = 0, wallMaxLength = 0, farCells = 0),
    SMALL(frequency = 3, mines = 14, lives = 1, hints = 1, walls = 2, wallMaxLength = 4, farCells = 3),
    MEDIUM(frequency = 4, mines = 28, lives = 2, hints = 1, walls = 3, wallMaxLength = 6, farCells = 5),
    LARGE(frequency = 5, mines = 45, lives = 3, hints = 2, walls = 4, wallMaxLength = 8, farCells = 8),
    HUGE(frequency = 6, mines = 65, lives = 4, hints = 2, walls = 5, wallMaxLength = 10, farCells = 11),
    GIANT(frequency = 8, mines = 125, lives = 5, hints = 3, walls = 7, wallMaxLength = 14, farCells = 18);

    val cellCount: Int get() = 10 * frequency * frequency + 2

    val wallMinLength: Int get() = maxOf(2, wallMaxLength / 2)
}
