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
 * line cell with its side and row (walls between rooms are part of the walls). Levels with rooms,
 * ocean, portals or locks also list the room count, the ocean cells, the portal pairs and the lock
 * thresholds; levels without them keep the shorter text, so their fingerprints do not depend on these
 * features. [GOLDEN] holds the expected fingerprint of every level.
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
        assertEquals(150, GOLDEN.size)
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
        private const val LINES_01_START = 37

        /** Line cells of lines-01 as "cell:side". */
        private val LINES_01_LINE_CELLS = listOf("78:4")

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
            "lines-01" to "03f3e9a3d5f44237",
            "lines-02" to "dd3d8e42273ecc27",
            "lines-03" to "e82bceda3a5cf4fc",
            "lines-04" to "9980be573895a205",
            "lines-05" to "f22f2c515ebac4e3",
            "lines-06" to "06e30085312c2ba8",
            "lines-07" to "82a036961f183705",
            "lines-08" to "c1ad09d6a0f25f0b",
            "lines-09" to "abcb814d574a8fd9",
            "lines-10" to "8e6fb2e77f4fb8f2",
            "lines-11" to "c2b406eec91779c9",
            "lines-12" to "81f54540ec15b85e",
            "lines-13" to "b6e77354aeafea66",
            "lines-14" to "3a166e81d4603d96",
            "lines-15" to "85a00466bf9346b2",
            "lines-16" to "d44e0301f392fc09",
            "lines-17" to "99242a234f8778b1",
            "lines-18" to "227b7ba66e50d409",
            "lines-19" to "14946fa2fdead7e1",
            "lines-20" to "5eb21a72476dfbeb",
            "mixed-01" to "a18530e59643f471",
            "mixed-02" to "84ae0f601346ed72",
            "mixed-03" to "6d5c9cf927eb6c9d",
            "mixed-04" to "98dcd9446d7277aa",
            "mixed-05" to "a47cc17267b8fc52",
            "mixed-06" to "12fbb6e3a1a0d598",
            "mixed-07" to "28e694b9146bca64",
            "mixed-08" to "37e2fd2aa0066b8e",
            "mixed-09" to "3c4d384a617933b1",
            "mixed-10" to "adf9e2fa5cba3f98",
            "mixed-11" to "eae7b290ee4b595a",
            "mixed-12" to "bb127a370505feaa",
            "mixed-13" to "de8b5a36a644ad82",
            "mixed-14" to "6a86b44ddfc02390",
            "mixed-15" to "cf4a04f293f88524",
            "mixed-16" to "bc2c8a1e0bb4a484",
            "mixed-17" to "73d5628ae8558063",
            "mixed-18" to "de71ee23d71125db",
            "mixed-19" to "d6f5be69a1d3f166",
            "mixed-20" to "8f6afe9384776dd0",
            "portals-01" to "5e61969b0dccdee2",
            "portals-02" to "7aa069aca7ccc445",
            "portals-03" to "6d9ee28cfafe8a30",
            "portals-04" to "a0a2c51c91190e70",
            "portals-05" to "de9f91f20de34bc2",
            "portals-06" to "b38a60fa886e0a47",
            "portals-07" to "6ac44a4687516382",
            "portals-08" to "7380a6fe8118890c",
            "portals-09" to "0895dc36dd727cd9",
            "portals-10" to "e6b713a30e44921f",
            "portals-11" to "8d3d9d749f970fc1",
            "portals-12" to "6fe8c023622dd335",
            "portals-13" to "bf5dbdc002c4d66e",
            "portals-14" to "5cc23d3e64781165",
            "portals-15" to "946ecf32a3baf7e8",
            "portals-16" to "4db5ab9928db8269",
            "portals-17" to "2330dc2161b6a66c",
            "portals-18" to "1b0c9a5e60ffd944",
            "portals-19" to "4a8db9a851433c97",
            "portals-20" to "7ece956a6b372470",
            "locks-01" to "44526b3b83fa9129",
            "locks-02" to "63c54f9c03480319",
            "locks-03" to "31532e2e47f817f3",
            "locks-04" to "7c02b67ec6269c4f",
            "locks-05" to "d7f1854f7011c5b8",
            "locks-06" to "1ac2ca5c8af66121",
            "locks-07" to "e3a3bb1f0f93561e",
            "locks-08" to "979b6f55f25e7740",
            "locks-09" to "dcb85769136fee36",
            "locks-10" to "0aaa9ede27b00bd2",
            "locks-11" to "e734c10f6abcbe5e",
            "locks-12" to "f69e11834efbb130",
            "locks-13" to "a68c878eb1f74e20",
            "locks-14" to "cbb32546e29670a3",
            "locks-15" to "1ac76c45348b5588",
            "locks-16" to "2a4bdc1a33fdd782",
            "locks-17" to "f9c65720a5dab421",
            "locks-18" to "3e4de33b52a07f73",
            "locks-19" to "05543e80da8c65af",
            "locks-20" to "1bf2b9f9f222a66e",
            "islands-01" to "7318c3f4f64db16e",
            "islands-02" to "34fec21a9eb89198",
            "islands-03" to "63bc004f09da1c1a",
            "islands-04" to "513d2b122e841f72",
            "islands-05" to "3cc51cbddf2fbc33",
            "islands-06" to "46dbb74114945bec",
            "islands-07" to "182663fbe5709d75",
            "islands-08" to "4194fca5012ade60",
            "islands-09" to "1da904dcf40f67df",
            "islands-10" to "78fc4a30e4088b9d",
            "islands-11" to "ff96b3bd348187cd",
            "islands-12" to "83e1ede9d2933a29",
            "islands-13" to "d7a1fc511a1f5a25",
            "islands-14" to "dac246d31b2a8228",
            "islands-15" to "13c7edd2ee14d34a",
            "islands-16" to "45f35b765b6389f7",
            "islands-17" to "3ca9bf585daa3d2a",
            "islands-18" to "3c200d3c3b1cc724",
            "islands-19" to "b012e10f9aeb2a50",
            "islands-20" to "b443387ee1d4f8cf",
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
         *
         * A level with rooms has `;rooms=` with its [LevelSpec.rooms] right after the frequency; levels
         * without rooms leave it out.
         *
         * Only when the level has them, three parts follow: `;ocean=` with the sorted ocean cells,
         * `;portals=` with every pair as `a-b` (lower cell first) in pair order, and `;locks=` with
         * every lock as `cell:threshold` in cell order.
         */
        fun describe(level: PreparedLevel): String {
            val layout = level.layout
            val cells = 0 until layout.scopes.size
            return buildString {
                append("id=").append(level.spec.id)
                append(";f=").append(level.spec.frequency)
                if (level.spec.rooms > 0) append(";rooms=").append(level.spec.rooms)
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
                val ocean = cells.filter { !layout.isPresent(it) }
                if (ocean.isNotEmpty()) append(";ocean=").append(ocean.joinToString(","))
                if (layout.portalPairCount > 0) {
                    append(";portals=")
                    append(
                        (0 until layout.portalPairCount).joinToString("|") { pair ->
                            val pairCells = cells.filter { layout.portalPair(it) == pair }
                            "${pairCells[0]}-${pairCells[1]}"
                        },
                    )
                }
                val locks = cells.filter { level.board.lockThreshold(it) > 0 }
                if (locks.isNotEmpty()) {
                    append(";locks=").append(locks.joinToString("|") { "$it:${level.board.lockThreshold(it)}" })
                }
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
