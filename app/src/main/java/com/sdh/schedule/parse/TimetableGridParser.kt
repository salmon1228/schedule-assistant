package com.sdh.schedule.parse

/**
 * 课表**网格**还原(纯 Kotlin,可单元测试)。
 *
 * 问题:课表截图 OCR 出来是一堆没有行列关系的文字块。
 * 例:表头是「星期一 星期二 …」,左边是「第1节 第2节 …」,
 * 中间全是课程格子 —— 按阅读顺序拼起来,根本不知道哪门课是星期几第几节。
 *
 * 解法:用每个文字块的**坐标**还原格子:
 *   ① 找到星期表头 → 得到 7 列的 x 中心
 *   ② 找到节次/时间那一列 → 得到每行的 y 中心
 *   ③ 剩下的文字块按坐标落到对应格子里
 *   ④ 每个格子内部再解析课程名/老师/地点/周次
 */
object TimetableGridParser {

    data class ParsedCourse(
        val name: String,
        val teacher: String = "",
        val location: String = "",
        val weekday: Int = 1,
        val startSlot: Int = 1,
        val slotCount: Int = 2,
        val startWeek: Int = 1,
        val endWeek: Int = 16,
        val weekParity: Int = 0
    )

    /** 输入用 LocalOcr.OcrBlock 的结构,但这里不依赖 Android(方便单测) */
    data class Block(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    private val WEEKDAY_TEXT = mapOf(
        "星期一" to 1, "周二" to 2, "星期二" to 2, "星期三" to 3, "星期四" to 4,
        "星期五" to 5, "星期六" to 6, "星期日" to 7, "星期天" to 7,
        "周一" to 1, "周三" to 3, "周四" to 4, "周五" to 5, "周六" to 6, "周日" to 7, "周天" to 7,
        "礼拜一" to 1, "礼拜二" to 2, "礼拜三" to 3, "礼拜四" to 4, "礼拜五" to 5, "礼拜六" to 6, "礼拜日" to 7
    )

    /** 「一」「二」……这种单字表头(出现在顶部一行时才算) */
    private val SINGLE_WEEKDAY = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7
    )

    private val SLOT_ROW = Regex("^第?\\s*(\\d{1,2})\\s*节?$")
    private val TIME_ROW = Regex("^(\\d{1,2})[:：](\\d{2})")

    /**
     * 主入口:把带坐标的文字块还原成课程表。
     * @return 识别出的课程;无法还原成网格时返回空列表(调用方可以退回按行解析)
     */
    fun parse(blocks: List<Block>): List<ParsedCourse> {
        if (blocks.size < 6) return emptyList()

        // ① 星期表头:文字命中星期,且大致在同一水平线上(取数量最多的那一行)
        val weekdayBlocks = blocks.mapNotNull { b ->
            val d = weekdayOf(b.text) ?: return@mapNotNull null
            d to b
        }
        if (weekdayBlocks.size < 3) return emptyList()

        // 按 y 聚类,取最像表头的一组(同一行且包含 >=3 个星期)
        val headerRow = pickHeaderRow(weekdayBlocks.map { it.second })
            ?: return emptyList()
        val columns: List<Pair<Int, Block>> = weekdayBlocks
            .filter { kotlin.math.abs(it.second.centerY - headerRow.centerY) < headerRow.height * 2 }
            .map { it.first to it.second }
            .sortedBy { it.second.centerX }
        if (columns.size < 3) return emptyList()

        // ② 节次行:优先用「第N节」,其次用时间(08:00),再退化成最左列的纯数字
        val headerWidth = headerRow.bottom - headerRow.top
        val slotBlocks = ArrayList<Triple<Int, Int, Block>>() // slot, y, block
        for (b in blocks) {
            if (b.centerY <= headerRow.centerY + headerWidth / 2) continue  // 表头以上的不管
            val t = b.text.trim()
            val slot = slotNumberOf(t) ?: continue
            slotBlocks.add(Triple(slot, b.centerY, b))
        }
        if (slotBlocks.size < 4) return emptyList()
        val slotRows = slotBlocks.distinctBy { it.first }.sortedBy { it.first }

        // ③ 每个文字块落到最近的列 / 行
        val headerTop = headerRow.top
        val cells = HashMap<Pair<Int, Int>, StringBuilder>()   // (weekday, slot) -> 文字
        val cellFirstTopY = HashMap<Pair<Int, Int>, Int>()
        val cellLastBottomY = HashMap<Pair<Int, Int>, Int>()

        for (b in blocks) {
            if (b.centerY <= headerRow.centerY + headerWidth / 2) continue
            if (slotNumberOf(b.text.trim()) != null) continue      // 节次列自己不算课程
            if (weekdayOf(b.text) != null) continue                // 第二处星期表头(如横表)不算
            val col = nearestColumn(columns, b.centerX) ?: continue
            val row = nearestSlotRow(slotRows, b.centerY) ?: continue
            val key = col to row.first
            cells.getOrPut(key) { StringBuilder() }.append(b.text.trim()).append(' ')
            cellFirstTopY[key] = minOf(cellFirstTopY[key] ?: b.top, b.top)
            cellLastBottomY[key] = maxOf(cellLastBottomY[key] ?: b.bottom, b.bottom)
        }
        if (cells.isEmpty()) return emptyList()

        // ④ 每个格子的文字 → 课程
        //    一个格子可能纵向跨多节(合并单元格),按"文字块高度 / 一行高度"估算连上几节
        val rowHeight = medianRowHeight(slotRows.map { it.second })
        val out = ArrayList<ParsedCourse>()
        for ((key, sb) in cells.entries) {
            val (weekday, slot) = key
            val text = sb.toString().trim()
            if (text.length < 2) continue
            val top = cellFirstTopY[key] ?: 0
            val bottom = cellLastBottomY[key] ?: 0
            val spanEstimate = if (rowHeight > 0) {
                (((bottom - top).toDouble() / rowHeight).roundToIntEven()).coerceIn(1, 4)
            } else 1
            val parsed = parseCell(text, weekday, slot, spanEstimate) ?: continue
            out.add(parsed)
        }

        // ⑤ 合并:同一门课在相邻节次重复出现(有些课表每个格子都重复画一遍)时合并
        return mergeAdjacent(out)
    }

    private fun Double.roundToIntEven(): Int = Math.round(this).toInt()

    private fun weekdayOf(text: String): Int? {
        val t = text.trim().replace(" ", "")
        WEEKDAY_TEXT[t]?.let { return it }
        if (t.length == 1) SINGLE_WEEKDAY[t]?.let { return it }
        // "周一 3-4节" 这种混在一起的,也认
        for ((k, v) in WEEKDAY_TEXT) if (t.contains(k)) return v
        val m = Regex("(?:周|星期|礼拜)([一二三四五六日天1-7])").find(t)
        if (m != null) {
            val c = m.groupValues[1]
            return SINGLE_WEEKDAY[c] ?: c.toIntOrNull()
        }
        return null
    }

    private fun slotNumberOf(text: String): Int? {
        val t = text.trim().replace(" ", "")
        SLOT_ROW.find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 1..15) return n
        }
        // 「08:00」这种时间行也算节次(按开始时间排序后再映射)
        TIME_ROW.find(t)?.let { return 100 + (it.groupValues[1].toIntOrNull() ?: 0) }
        return null
    }

    private fun pickHeaderRow(weekdayBlocks: List<Block>): Block? {
        if (weekdayBlocks.isEmpty()) return null
        // 取中心 y 最靠上、且同一行内块数最多的那一组
        val sorted = weekdayBlocks.sortedBy { it.centerY }
        var best: List<Block> = emptyList()
        for (seed in sorted) {
            val row = weekdayBlocks.filter { kotlin.math.abs(it.centerY - seed.centerY) < seed.height * 2 }
            if (row.size > best.size) best = row
        }
        return best.minByOrNull { it.centerY }
    }

    private fun nearestColumn(columns: List<Pair<Int, Block>>, x: Int): Int? {
        if (columns.size < 2) return columns.firstOrNull()?.first
        var best: Int? = null
        var bestDist = Int.MAX_VALUE
        for (i in columns.indices) {
            val c = columns[i]
            // 列边界:相邻两列中心的中点
            val leftBound = if (i == 0) Int.MIN_VALUE else (columns[i - 1].second.centerX + c.second.centerX) / 2
            val rightBound = if (i == columns.size - 1) Int.MAX_VALUE else (c.second.centerX + columns[i + 1].second.centerX) / 2
            val d = when {
                x < leftBound -> leftBound - x
                x > rightBound -> x - rightBound
                else -> 0
            }
            if (d < bestDist) {
                bestDist = d
                best = c.first
            }
        }
        return best
    }

    private fun nearestSlotRow(rows: List<Triple<Int, Int, Block>>, y: Int): Triple<Int, Int, Block>? {
        if (rows.isEmpty()) return null
        return rows.minByOrNull { kotlin.math.abs(it.second - y) }
    }

    private fun medianRowHeight(ys: List<Int>): Int {
        if (ys.size < 2) return 0
        val diffs = ys.sorted().zipWithNext { a, b -> b - a }.filter { it > 0 }
        if (diffs.isEmpty()) return 0
        return diffs.sorted()[diffs.size / 2]
    }

    /** 把一个格子里的文字解析成课程 */
    private fun parseCell(text: String, weekday: Int, slot: Int, spanGuess: Int): ParsedCourse? {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2) return null

        // 周次
        var startWeek = 1
        var endWeek = 16
        var parity = 0
        Regex("(\\d{1,2})\\s*[-~—–至]\\s*(\\d{1,2})\\s*周").find(clean)?.let {
            startWeek = (it.groupValues[1].toIntOrNull() ?: 1).coerceAtLeast(1)
            endWeek = (it.groupValues[2].toIntOrNull() ?: 16).coerceAtLeast(startWeek)
        }
        Regex("第?\\s*(\\d{1,2})\\s*[-~—–至]\\s*(\\d{1,2})\\s*周").find(clean)?.let {
            startWeek = (it.groupValues[1].toIntOrNull() ?: startWeek).coerceAtLeast(1)
            endWeek = (it.groupValues[2].toIntOrNull() ?: endWeek).coerceAtLeast(startWeek)
        }
        if (clean.contains("单周")) parity = 1
        if (clean.contains("双周")) parity = 2

        // 节次(格子内有时也写 "1-2节")
        var startSlot = slot
        var span = spanGuess
        Regex("(\\d{1,2})\\s*[-~—–]\\s*(\\d{1,2})\\s*节").find(clean)?.let {
            val a = it.groupValues[1].toIntOrNull()
            val b = it.groupValues[2].toIntOrNull()
            if (a != null && b != null && b >= a) {
                startSlot = a.coerceIn(1, 15)
                span = (b - a + 1).coerceIn(1, 4)
            }
        }

        // 地点三种写法:①带楼/室/馆字样 ②字母房号 A301 ③纯数字房号 1101 / 5-310
        // (周次和节次在上面的步骤里已经剥掉了,所以剩下的裸数字基本就是房号)
        val locWord = Regex("[\\u4e00-\\u9fa5A-Za-z0-9]{0,8}(?:楼|室|馆|校区|教室|实验|中心)[\\u4e00-\\u9fa5A-Za-z0-9]{0,10}")
            .find(clean)?.value?.trim().orEmpty()
        val locCode = Regex("(?:^|\\s)([A-Za-z]{1,3}\\d{2,4}(?:[-—]\\d{1,4})?)(?=\\s|$)")
            .find(clean)?.groupValues?.get(1)?.trim().orEmpty()
        val locNumber = Regex("(?:^|\\s)(\\d{3,4}|\\d{1,2}[-—]\\d{3})(?=\\s|$)")
            .find(clean)?.groupValues?.get(1)?.trim().orEmpty()
        val location = locWord.ifBlank { locCode.ifBlank { locNumber } }

        // 老师:优先「张老师」这种带称呼的;否则找 2~3 字、首字是常见姓氏的姓名
        // (真实课表里老师常常只写名字,比如「邵牧好」「马铭阳」)
        var teacher = Regex("[\\u4e00-\\u9fa5]{1,4}(?:老师|教授|讲师)").find(clean)?.value.orEmpty()
        if (teacher.isBlank()) {
            teacher = clean.split(' ', '\n', '|', ',', '，', '/')
                .map { it.trim() }
                .firstOrNull { tok ->
                    tok.length in 2..3 &&
                        tok.all { it.code in 0x4E00..0x9FA5 } &&
                        SURNAMES.contains(tok[0].toString())
                }.orEmpty()
        }

        // 课程名:去掉周次/节次/地点/老师后剩下的中文片段里最长的那个
        var rest = clean
            .replace(Regex("(\\d{1,2})\\s*[-~—–至]\\s*(\\d{1,2})\\s*周"), " ")
            .replace(Regex("(\\d{1,2})\\s*[-~—–]\\s*(\\d{1,2})\\s*节"), " ")
            .replace("单周", " ").replace("双周", " ")
        if (location.isNotBlank()) rest = rest.replace(location, " ")
        if (teacher.isNotBlank()) rest = rest.replace(teacher, " ")

        val name = rest.split(' ', '\n', '|', ',', '，')
            .map { it.trim() }
            .filter { it.length in 2..24 && it.any { ch -> ch.code > 0x4E00 } }
            .filter { cand -> NOT_COURSE.none { cand.contains(it) } }
            .maxByOrNull { it.length }
            ?: return null

        return ParsedCourse(
            name = name,
            teacher = teacher,
            location = location,
            weekday = weekday.coerceIn(1, 7),
            startSlot = startSlot.coerceIn(1, 15),
            slotCount = span.coerceIn(1, 6),
            startWeek = startWeek,
            endWeek = endWeek,
            weekParity = parity
        )
    }

    private val NOT_COURSE = listOf(
        "课程表", "周次", "时间", "节次", "上午", "下午", "晚上", "备注", "作息", "星期", "星期一"
    )

    /**
     * 常见姓氏(百家姓常用部分)。
     * 用途:真实课表里老师常常只写姓名不带「老师」二字,靠姓氏判断比瞎猜可靠。
     */
    private val SURNAMES: Set<String> = (
        "王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘于蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤"
        ).map { it.toString() }.toSet()

    /** 相邻节次里重复出现的同一门课 → 合并成一门(连上多节) */
    private fun mergeAdjacent(list: List<ParsedCourse>): List<ParsedCourse> {
        val out = ArrayList<ParsedCourse>()
        for (c in list.sortedWith(compareBy({ it.weekday }, { it.startSlot }))) {
            val prev = out.lastOrNull()
            if (prev != null &&
                prev.weekday == c.weekday &&
                prev.name == c.name &&
                prev.location == c.location &&
                c.startSlot in (prev.startSlot + 1)..(prev.startSlot + prev.slotCount + 1)
            ) {
                // 合并:延长 span
                val newEnd = maxOf(prev.startSlot + prev.slotCount - 1, c.startSlot + c.slotCount - 1)
                out[out.size - 1] = prev.copy(slotCount = (newEnd - prev.startSlot + 1).coerceAtMost(6))
            } else {
                out.add(c)
            }
        }
        return out
    }
}
