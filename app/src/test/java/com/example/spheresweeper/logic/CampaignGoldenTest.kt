package com.example.spheresweeper.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Freezes the boards of all campaign levels.
 *
 * Each level is built with [Campaign.buildLevel] and reduced to a fingerprint: 64-bit FNV-1a over the
 * UTF-8 bytes of a canonical text ([describe]) holding the id, the frequency, the start cell, the
 * sorted mines, every wall as its corner indices in generator order, the sorted far cells and every
 * line cell with its side and row. [GOLDEN] holds the expected fingerprint of every level.
 *
 * A changed fingerprint means players get a different board for that level: progress and stars they
 * earned would then belong to a board they never saw. Changes in [StableRandom], [BoardLayout],
 * [NoGuessGenerator], [LogicSolver], [SphereGeometry], the campaign tuning or the level seeds can all
 * cause it. Before release, update the values only on purpose. After release, change a level's board
 * only together with a plan for existing progress.
 *
 * To record new values on purpose, run [main] with the compiled main and test classes on the class
 * path, e.g. `java -cp <classes>:<kotlin-stdlib.jar> com.example.spheresweeper.logic.CampaignGoldenTest`.
 * It prints the whole map in source form, ready to replace [GOLDEN]. A failing run also lists every
 * changed id with its new value.
 */
class CampaignGoldenTest {

    @Test
    fun everyLevelMatchesItsRecordedFingerprint() {
        val actual = fingerprints()
        val problems = ArrayList<String>()
        for ((id, value) in actual) {
            val expected = GOLDEN[id]
            when {
                expected == null -> problems.add("$id: not recorded, now $value")
                expected != value -> problems.add("$id: expected $expected, now $value")
            }
        }
        for (id in GOLDEN.keys - actual.keys) problems.add("$id: recorded but no longer in the campaign")
        if (problems.isNotEmpty()) {
            fail(
                "${problems.size} campaign level(s) changed; players would get different boards:\n" +
                    problems.joinToString("\n"),
            )
        }
        assertEquals(90, GOLDEN.size)
    }

    /** A few levels written out in plain numbers, so a failure here shows what moved. */
    @Test
    fun firstLevelsLookAsRecorded() {
        val trunk = build("trunk-01")
        assertEquals("trunk-01 start", TRUNK_01_START, trunk.start)
        assertArrayEquals("trunk-01 mines", TRUNK_01_MINES, trunk.mines)

        val walls = build("walls-01")
        assertEquals("walls-01 start", WALLS_01_START, walls.start)
        assertArrayEquals("walls-01 first mines", WALLS_01_FIRST_MINES, walls.mines.copyOf(WALLS_01_FIRST_MINES.size))
        assertEquals("walls-01 wall count", WALLS_01_WALLS.size, walls.layout.walls.size)
        for (w in WALLS_01_WALLS.indices) {
            assertArrayEquals("walls-01 wall $w", WALLS_01_WALLS[w], walls.layout.walls[w])
        }

        val lines = build("lines-01")
        assertEquals("lines-01 start", LINES_01_START, lines.start)
        val lineCells = (0 until lines.layout.scopes.size)
            .filter { lines.layout.isLine(it) }
            .map { "$it:${lines.layout.lineSide(it)}" }
        assertEquals("lines-01 line cells", LINES_01_LINE_CELLS, lineCells)
    }

    companion object {
        private const val TRUNK_01_START = 26
        private val TRUNK_01_MINES = intArrayOf(14, 17, 33, 35, 38)
        private const val WALLS_01_START = 21
        private val WALLS_01_FIRST_MINES = intArrayOf(2, 9, 27, 35, 39)
        private val WALLS_01_WALLS = arrayOf(intArrayOf(480, 481, 482, 319, 320), intArrayOf(522, 527, 473, 468))
        private const val LINES_01_START = 61

        /** Line cells of lines-01 as "cell:side". */
        private val LINES_01_LINE_CELLS = listOf("38:0", "75:0")

        /** Expected fingerprint of every campaign level, by id. Regenerate with [main]. */
        private val GOLDEN: Map<String, String> = mapOf(
            "trunk-01" to "7c08d83ad27f7738",
            "trunk-02" to "dc11b086eee3048c",
            "trunk-03" to "6f990c9980d6299a",
            "trunk-04" to "67510d2795093206",
            "trunk-05" to "005afe593cf16faf",
            "trunk-06" to "07e93623f66f395b",
            "trunk-07" to "8e16e6d74de9f9d3",
            "trunk-08" to "4baac9ff06e0f1e0",
            "trunk-09" to "f4dc8f6597f37587",
            "trunk-10" to "34bd7f9c84f339d2",
            "walls-01" to "60ff0dacf3abdcc9",
            "walls-02" to "dcebe54b119797c3",
            "walls-03" to "c6304bda1d109aa0",
            "walls-04" to "bf4ae0d5d8ae31d9",
            "walls-05" to "6fbacbd7ccb4863c",
            "walls-06" to "693b66a7029a796c",
            "walls-07" to "38f93d402ec8fc89",
            "walls-08" to "aec9b6c89fd56902",
            "walls-09" to "0ef8a2ba9343ef86",
            "walls-10" to "2d3a600202ef78cf",
            "walls-11" to "373cbfe45cb37c89",
            "walls-12" to "b40a56f6d486a5d0",
            "walls-13" to "1dcd3c50e8310099",
            "walls-14" to "6abf28d0e53b551b",
            "walls-15" to "b0f8ecb52e5ae48d",
            "walls-16" to "74f6a97326c95485",
            "walls-17" to "6b0c53670974a161",
            "walls-18" to "3566f1b37e562c25",
            "walls-19" to "6eb665673727650f",
            "walls-20" to "a55d6696a589de0e",
            "far-01" to "1c5b288e0c8a18dc",
            "far-02" to "0340da6e4ec13573",
            "far-03" to "315dcc46d32d475d",
            "far-04" to "07a86e476f39add6",
            "far-05" to "f95fa8c86577386d",
            "far-06" to "fc099156b73e9c16",
            "far-07" to "fc3caa4438e1c6e5",
            "far-08" to "bf278a15624cc61e",
            "far-09" to "460f9c5f34867ae0",
            "far-10" to "d3bbfc1dafba0bd7",
            "far-11" to "d8cc8629b0f8ecde",
            "far-12" to "03c0fa58e57a4d29",
            "far-13" to "0ccb1ec179e5e0f2",
            "far-14" to "12e701a57cc786e5",
            "far-15" to "e679c66b4f49012a",
            "far-16" to "689f82c3eb961561",
            "far-17" to "983113241863701f",
            "far-18" to "4d4c2faeda4b3ef0",
            "far-19" to "9fac20ea00a11696",
            "far-20" to "5f6fc03546a7eb82",
            "lines-01" to "ca11b1fa4147c982",
            "lines-02" to "8b640c613e3df81d",
            "lines-03" to "308b22b8a663f0ed",
            "lines-04" to "01cbd17856ab25a4",
            "lines-05" to "04146b7a5202fff5",
            "lines-06" to "2f40ac3e6405f4f5",
            "lines-07" to "7907e9a4ab5ffd84",
            "lines-08" to "8f57fb3a1ad5fa6b",
            "lines-09" to "d94df4d6ea2808d2",
            "lines-10" to "e2641c3b0cd4b72a",
            "lines-11" to "de714b330191600f",
            "lines-12" to "80c8602d17737032",
            "lines-13" to "c5cc7f0b1bcb9f66",
            "lines-14" to "4f0ad7ef15117ac0",
            "lines-15" to "7682680a2b6ac654",
            "lines-16" to "40b64f686b5c837b",
            "lines-17" to "6f098e5aa3aa2d7e",
            "lines-18" to "3214a4abf37dcf55",
            "lines-19" to "c908c90b3dbf5542",
            "lines-20" to "f21eaf21653d4359",
            "mixed-01" to "04f85f548670d3ce",
            "mixed-02" to "07d73140f64278f8",
            "mixed-03" to "3087701981a13020",
            "mixed-04" to "3769a12af88c27e1",
            "mixed-05" to "0aa70e775035a2e4",
            "mixed-06" to "4927b11a96da654a",
            "mixed-07" to "fee4f58c36740d3f",
            "mixed-08" to "2784677ea3dd51e0",
            "mixed-09" to "8a345d876047addd",
            "mixed-10" to "e4e684ba87e1b8e5",
            "mixed-11" to "c931693a58e30555",
            "mixed-12" to "76ceb00ae60b026b",
            "mixed-13" to "31994183f3ed9888",
            "mixed-14" to "537fa840875854a9",
            "mixed-15" to "c0c3dfce0efdb553",
            "mixed-16" to "f839ab5c132234d0",
            "mixed-17" to "2e69d0de84e41126",
            "mixed-18" to "8319b183fff28f96",
            "mixed-19" to "a80acd71498c001b",
            "mixed-20" to "d07f253e36579748",
        )

        private fun build(id: String): PreparedLevel {
            val spec = Campaign.byId(id) ?: error("no level $id")
            return Campaign.buildLevel(spec, SphereGeometry.build(spec.frequency))
        }

        /** Fingerprint of every level in [Campaign.all] order. */
        fun fingerprints(): LinkedHashMap<String, String> {
            val geometries = HashMap<Int, SphereGeometry>()
            val result = LinkedHashMap<String, String>()
            for (spec in Campaign.all) {
                val geometry = geometries.getOrPut(spec.frequency) { SphereGeometry.build(spec.frequency) }
                result[spec.id] = fingerprint(Campaign.buildLevel(spec, geometry))
            }
            return result
        }

        fun fingerprint(level: PreparedLevel): String =
            fnv1a64(describe(level).toByteArray(Charsets.UTF_8)).toULong().toString(16).padStart(16, '0')

        /**
         * Canonical text of a built level, for example
         * `id=walls-01;f=3;start=40;mines=2,17;walls=[5,6,7][...];far=;lines=12:3:13,14,15|...`.
         * Walls keep the order and direction the generator gives them, far and line cells are sorted.
         */
        fun describe(level: PreparedLevel): String {
            val layout = level.layout
            val cells = 0 until layout.scopes.size
            return buildString {
                append("id=").append(level.spec.id)
                append(";f=").append(level.spec.frequency)
                append(";start=").append(level.start)
                append(";mines=").append(level.mines.sorted().joinToString(","))
                append(";walls=")
                for (wall in layout.walls) append(wall.joinToString(",", "[", "]"))
                append(";far=").append(cells.filter { layout.isFar(it) }.joinToString(","))
                append(";lines=")
                append(
                    cells.filter { layout.isLine(it) }.joinToString("|") {
                        "$it:${layout.lineSide(it)}:${layout.linePath(it).joinToString(",")}"
                    },
                )
            }
        }

        /** 64-bit FNV-1a over [bytes]. */
        private fun fnv1a64(bytes: ByteArray): Long {
            var hash = 0xcbf29ce484222325uL.toLong()
            for (b in bytes) {
                hash = hash xor (b.toLong() and 0xFF)
                hash *= 0x100000001b3L
            }
            return hash
        }

        /** Prints [GOLDEN] in source form for the current generators. */
        @JvmStatic
        fun main(args: Array<String>) {
            println("        private val GOLDEN: Map<String, String> = mapOf(")
            for ((id, value) in fingerprints()) println("            \"$id\" to \"$value\",")
            println("        )")
        }
    }
}
