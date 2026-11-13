package com.ipvio.stream

/**
 * Small QR code generator (round 21), without a library: byte mode, error correction M, version 1–11
 * (up to 250 characters). Enough for addresses like http://192.168.0.20:8080/ or https://www.google.com/device.
 * The result is a grid: true = dark module.
 */
object QrCode {
    private val ECC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30)   // level M, from version 1
    private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5)
    const val MAX_VERSION = 11

    private fun rawModules(ver: Int): Int {
        var r = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val n = ver / 7 + 2
            r -= (25 * n - 10) * n - 55
            if (ver >= 7) r -= 36
        }
        return r
    }

    private fun dataCodewords(ver: Int) = rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver]

    /** Largest number of characters (bytes) that still fits. */
    fun maxBytes(): Int = dataCodewords(MAX_VERSION) - 3

    /** Grid for the text, or null if it is too long. */
    fun encode(text: String): Array<BooleanArray>? {
        val data = text.toByteArray(Charsets.UTF_8)
        var ver = 1
        while (ver <= MAX_VERSION) {
            val cap = dataCodewords(ver) * 8
            val countBits = if (ver >= 10) 16 else 8
            if (4 + countBits + data.size * 8 <= cap) break
            ver++
        }
        if (ver > MAX_VERSION) return null
        val countBits = if (ver >= 10) 16 else 8
        val cap = dataCodewords(ver) * 8

        val bits = ArrayList<Int>()
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((v ushr i) and 1) }
        put(4, 4); put(data.size, countBits)
        for (b in data) put(b.toInt() and 0xFF, 8)
        put(0, minOf(4, cap - bits.size))
        while (bits.size % 8 != 0) bits.add(0)
        var pad = 0xEC
        while (bits.size < cap) { put(pad, 8); pad = pad xor (0xEC xor 0x11) }
        val dataBytes = ByteArray(bits.size / 8)
        for (i in bits.indices) dataBytes[i ushr 3] = (dataBytes[i ushr 3].toInt() or (bits[i] shl (7 - (i and 7)))).toByte()

        val all = interleave(dataBytes, ver)
        return Builder(ver).build(all)
    }

    private fun interleave(data: ByteArray, ver: Int): ByteArray {
        val nb = BLOCKS[ver]
        val eccLen = ECC_PER_BLOCK[ver]
        val raw = rawModules(ver) / 8
        val shortBlocks = nb - raw % nb
        val shortLen = raw / nb
        val div = rsDivisor(eccLen)
        val blocks = ArrayList<ByteArray>()
        val eccs = ArrayList<ByteArray>()
        var k = 0
        for (i in 0 until nb) {
            val len = shortLen - eccLen + if (i < shortBlocks) 0 else 1
            val d = data.copyOfRange(k, k + len)
            k += len
            blocks.add(d)
            eccs.add(rsRemainder(d, div))
        }
        val out = ByteArray(raw)
        var o = 0
        val maxData = blocks.maxOf { it.size }
        for (i in 0 until maxData) for (b in blocks) if (i < b.size) out[o++] = b[i]
        for (i in 0 until eccLen) for (e in eccs) out[o++] = e[i]
        return out
    }

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun rsDivisor(degree: Int): IntArray {
        val r = IntArray(degree)
        r[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                r[j] = gfMul(r[j], root)
                if (j + 1 < degree) r[j] = r[j] xor r[j + 1]
            }
            root = gfMul(root, 0x02)
        }
        return r
    }

    private fun rsRemainder(data: ByteArray, div: IntArray): ByteArray {
        val r = IntArray(div.size)
        for (b in data) {
            val f = (b.toInt() and 0xFF) xor r[0]
            for (i in 0 until r.size - 1) r[i] = r[i + 1]
            r[r.size - 1] = 0
            for (i in r.indices) r[i] = r[i] xor gfMul(div[i], f)
        }
        return ByteArray(r.size) { r[it].toByte() }
    }

    private class Builder(val ver: Int) {
        val size = ver * 4 + 17
        val m = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean) { m[y][x] = dark; fn[y][x] = true }

        fun build(codewords: ByteArray): Array<BooleanArray> {
            drawFunction()
            place(codewords)
            var best = 0
            var bestPen = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(mask); formatBits(mask)
                val p = penalty()
                if (p < bestPen) { bestPen = p; best = mask }
                applyMask(mask)
            }
            applyMask(best); formatBits(best)
            return Array(size) { m[it].copyOf() }
        }

        fun drawFunction() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val pos = alignPositions()
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            formatBits(0)
            if (ver >= 7) {
                var rem = ver
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (ver shl 12) or rem
                for (i in 0 until 18) {
                    val c = ((bits ushr i) and 1) != 0
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(a, b, c); set(b, a, c)
                }
            }
        }

        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(Math.abs(dx), Math.abs(dy))
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) set(x, y, d != 2 && d != 4)
            }
        }

        fun alignPositions(): IntArray {
            if (ver == 1) return IntArray(0)
            val n = ver / 7 + 2
            val step = (ver * 4 + n * 2 + 1) / (n * 2 - 2) * 2
            val r = IntArray(n)
            r[0] = 6
            var p = size - 7
            for (i in n - 1 downTo 1) { r[i] = p; p -= step }
            return r
        }

        /** Level M has the format identifier 0. */
        fun formatBits(mask: Int) {
            val data = (0 shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        fun place(cw: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val up = ((right + 1) and 2) == 0
                    val y = if (up) size - 1 - vert else vert
                    if (!fn[y][x] && i < cw.size * 8) {
                        m[y][x] = ((cw[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val inv = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (inv && !fn[y][x]) m[y][x] = !m[y][x]
            }
        }

        fun penalty(): Int {
            var p = 0
            // Same cells in a row/column
            for (a in 0 until size) for (rowMode in 0..1) {
                var run = 1
                for (b in 1 until size) {
                    val cur = if (rowMode == 0) m[a][b] else m[b][a]
                    val prev = if (rowMode == 0) m[a][b - 1] else m[b - 1][a]
                    if (cur == prev) { run++; if (run == 5) p += 3 else if (run > 5) p++ } else run = 1
                }
            }
            // 2×2 blocks
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3
            }
            // Patterns like in the corner squares
            val pat = booleanArrayOf(true, false, true, true, true, false, true)
            for (a in 0 until size) for (rowMode in 0..1) for (s in 0..size - 7) {
                var ok = true
                for (k in 0..6) {
                    val v = if (rowMode == 0) m[a][s + k] else m[s + k][a]
                    if (v != pat[k]) { ok = false; break }
                }
                if (ok) p += 40
            }
            // Ratio dark/light
            var dark = 0
            for (row in m) for (c in row) if (c) dark++
            val total = size * size
            p += (Math.abs(dark * 20 - total * 10) + total - 1) / total * 10 - 10
            return p
        }
    }
}
