package org.impfai.hermes.engine

/**
 * 中文文本規範化 —— 逐行移植自 backend/hermes_shanghan/textutil.py。
 *
 * 不變量（跨語言一致性測試的基準）：
 *  - foldVariants：異體字折疊（脇→脅 鞕→硬 欬→咳 濇→澀 幾→几），
 *    單字映射保持字符串長度不變；
 *  - s2t：領域簡→繁映射（只覆蓋傷寒論領域詞彙）；
 *  - normalizeQuery = foldVariants(s2t(trim))；
 *  - tokenize：CJK（U+3400..U+9FFF）單字 + 相鄰雙字，與 Python
 *    `[㐀-鿿]` 正則同一碼段。
 */
object TextNorm {

    private val VARIANT_MAP = mapOf(
        '脇' to '脅', '鞕' to '硬', '欬' to '咳', '濇' to '澀', '幾' to '几',
    )

    // 與 textutil._S2T_PAIRS 逐字相同；解析規則亦相同：
    // 兩字一組，首尾相同或組長不為 2 者跳過。
    private const val S2T_PAIRS =
        "恶惡 风風 发發 热熱 无無 呕嘔 干乾 烦煩 谵譫 语語 满滿 胁脅 头頭 项項 强強 " +
        "脉脈 紧緊 缓緩 数數 细細 汤湯 黄黃 龙龍 参參 调調 气氣 阳陽 阴陰 泻瀉 连連 " +
        "胶膠 乌烏 当當 归歸 猪豬 姜薑 与與 后後 误誤 证證 经經 体體 实實 虚虛 师師 " +
        "沉沈 里裏 表表 临臨 床牀 药藥 剂劑 医醫 论論 伤傷 寒寒 杂雜 张張 机機 " +
        "条條 辨辨 删刪 难難 红紅 紫紫 觉覺 转轉 输輸 阐闡 释釋 减減 协協 闷悶 呃呃 " +
        "哕噦 衄衄 疼疼 痛痛 痞痞 厥厥 利利 秘祕 结結 胸胸 烧燒 针針 灸灸 熏熏 熨熨 " +
        "悸悸 眩眩 冒冒 渴渴 饮飲 食食 谷穀 溏溏 脓膿 血血 尿尿 溲溲 汗汗 吐吐 下下 " +
        "温溫 清清 补補 救救 逆逆 传傳 变變 愈癒 死死 生生 长長 短短 迟遲 疾疾 滑滑 " +
        "涩澀 弦弦 微微 弱弱 洪洪 大大 芤芤 革革 动動 促促 代代 牢牢 濡濡 散散 伏伏 " +
        "国國 学學 书書 读讀 万萬 亿億 历歷 历曆 复復 复複 见見 观觀 视視 听聽 闻聞 " +
        "问問 诊診 络絡 腑腑 脏臟 肾腎 脾脾 肺肺 肝肝 胆膽 肠腸 胃胃 膀膀 胱胱 焦焦 " +
        "营營 卫衛 荣榮 个個 们們 这這 对對 时時 将將 应應 须須 须鬚 单單 双雙 几幾 " +
        "儿兒 处處 内內 两兩 仅僅 从從 众眾 优優 会會 伞傘 备備 储儲 兰蘭 关關 兴興 " +
        "兹茲 养養 兼兼 决決 况況 净淨 准準 凉涼 凄淒 减减 凑湊 亏虧 云雲 互互 " +
        "井井 亚亞 些些 交交 亥亥 亦亦 产產 享享 亲親 仁仁 仆僕 介介 仍仍 仓倉 " +
        "仔仔 他他 仗仗 付付 仙仙 代代 令令 以以 仪儀 件件 价價 任任 份份 仿彷 " +
        "瓜瓜 瓣瓣 甘甘 甚甚 甜甜 椒椒 茱茱 萸萸 苓苓 术朮 桂桂 枝枝 芍芍 " +
        "草草 麻麻 杏杏 仁仁 石石 膏膏 知知 母母 粳粳 米米 葛葛 根根 柴柴 胡胡 " +
        "芩芩 夏夏 枣棗 蛔蚘 栀梔 豉豉 翁翁 柏柏 皮皮 茵茵 陈陳 蒿蒿 泽澤 " +
        "胆膽 矾礬 蜜蜜 煎煎 导導 赤赤 滑滑 蛤蛤 文文 灶灶 中中 " +
        "极極 标標 准准 确確 诉訴 销銷 镇鎮 错錯 钱錢 铁鐵 铃鈴 银銀 镜鏡 闭閉 " +
        "问问 间間 闰閏 闲閒 闹鬧 阅閱 阵陣 阶階 际際 陆陸 陈陈 降降 限限 院院 " +
        "页頁 顶頂 顷頃 项项 顺順 颂頌 预預 领領 频頻 颗顆 题題 颜顏 额額 风风 " +
        "饥飢 饱飽 饮饮 饴飴 饼餅 馆館 首首 香香 马馬 驱驅 验驗 骨骨 高高 鬼鬼 " +
        "鱼魚 鸟鳥 鸡雞 麦麥 麻麻 黑黑 默默 鼓鼓 鼻鼻 齐齊 齿齒 龈齦 龟龜 "

    val S2T: Map<Char, Char> = buildMap {
        for (pair in S2T_PAIRS.trim().split(Regex("\\s+"))) {
            if (pair.length == 2 && pair[0] != pair[1]) put(pair[0], pair[1])
        }
    }

    // 繁→簡：由 S2T 反轉派生 + 高頻補充（textutil._T2S_EXTRA 同源）。
    private val T2S_EXTRA = mapOf(
        '裡' to '里', '係' to '系', '堅' to '坚', '髒' to '脏', '灣' to '湾',
        '億' to '亿', '點' to '点', '團' to '团', '戰' to '战', '邊' to '边',
        '隨' to '随', '證' to '证', '與' to '与', '為' to '为', '後' to '后',
        '總' to '总', '覽' to '览', '檢' to '检', '檔' to '档', '閉' to '闭',
        '環' to '环', '庫' to '库', '於' to '于', '運' to '运', '鑒' to '鉴',
        '練' to '练', '習' to '习', '題' to '题', '擴' to '扩', '濾' to '滤',
        '譜' to '谱', '關' to '关', '讀' to '读', '體' to '体', '類' to '类',
        '維' to '维', '評' to '评', '測' to '测', '標' to '标', '註' to '注',
        '議' to '议', '錄' to '录', '選' to '选', '擇' to '择', '鍵' to '键',
        '統' to '统', '計' to '计', '網' to '网', '絡' to '络', '圖' to '图',
        '層' to '层', '廣' to '广', '節' to '节', '術' to '术', '語' to '语',
        '識' to '识', '別' to '别', '來' to '来', '現' to '现', '詢' to '询',
        '務' to '务', '動' to '动', '詞' to '词', '書' to '书', '籍' to '籍',
        '義' to '义', '釋' to '释', '問' to '问', '答' to '答', '門' to '门',
        '間' to '间', '頁' to '页', '顯' to '显', '示' to '示', '轉' to '转',
        '換' to '换', '載' to '载', '續' to '续', '鏈' to '链', '驗' to '验',
    )

    val T2S: Map<Char, Char> = buildMap {
        for ((s, t) in S2T) if (s != t) put(t, s)
        putAll(T2S_EXTRA)
    }

    fun foldVariants(text: String): String =
        text.map { VARIANT_MAP[it] ?: it }.joinToString("")

    fun s2t(text: String): String =
        text.map { S2T[it] ?: it }.joinToString("")

    /** 繁→簡，僅供顯示層；原文以繁體為準。 */
    fun t2s(text: String): String =
        text.map { T2S[it] ?: it }.joinToString("")

    fun normalizeQuery(text: String): String = foldVariants(s2t(text.trim()))

    private fun isCjk(c: Char): Boolean = c.code in 0x3400..0x9FFF

    fun cjkChars(text: String): List<Char> = text.filter { isCjk(it) }.toList()

    /** CJK 單字 + 相鄰雙字（與 Python tokenize 一致）。 */
    fun tokenize(text: String): List<String> {
        val chars = cjkChars(text)
        val tokens = ArrayList<String>(chars.size * 2)
        chars.forEach { tokens.add(it.toString()) }
        for (i in 0 until chars.size - 1) {
            tokens.add("${chars[i]}${chars[i + 1]}")
        }
        return tokens
    }
}
