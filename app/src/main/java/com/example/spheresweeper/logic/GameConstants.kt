package com.example.spheresweeper.logic

/**
 * Available board sizes.
 *
 * [frequency] is the icosahedron subdivision frequency n; the board then has 10 * n^2 + 2 cells.
 * [mines] is the number of mines for that size. This is the single place to tune difficulty.
 */
enum class BoardSize(val frequency: Int, val mines: Int) {
    TINY(2, 6),
    SMALL(3, 14),
    MEDIUM(4, 28),
    LARGE(5, 45),
    HUGE(6, 65),
    GIANT(8, 125);

    val cellCount: Int get() = 10 * frequency * frequency + 2
}
