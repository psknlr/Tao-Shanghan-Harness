package org.impfai.hermes.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.impfai.hermes.AppContainer
import org.impfai.hermes.engine.AnnotationStore
import org.impfai.hermes.engine.LibraryStore
import org.impfai.hermes.ui.common.NoticeBar
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer
import kotlin.math.roundToInt

/** 閱讀主題（Kindle 式）：紙感米黃 / 純白 / 豆沙綠 / 夜間。 */
data class ReaderTheme(
    val key: String, val label: String,
    val bg: Color, val fg: Color, val highlight: Color, val accent: Color,
)

val READER_THEMES = listOf(
    ReaderTheme("paper", "米黄", Color(0xFFFAF3E3), Color(0xFF3B3226),
        Color(0x59E8C96A), Color(0xFF8C6B2F)),
    ReaderTheme("white", "纯白", Color(0xFFFFFFFF), Color(0xFF202320),
        Color(0x4DFFD54F), Color(0xFF2E5E4E)),
    ReaderTheme("green", "豆沙绿", Color(0xFFCCE8CF), Color(0xFF23392B),
        Color(0x59A9D18E), Color(0xFF3E6B4F)),
    ReaderTheme("night", "夜间", Color(0xFF15130F), Color(0xFFD8CFBE),
        Color(0x40E8C96A), Color(0xFFB59A55)),
)

/** 首行縮進兩全角空格：縮進計入顯示座標，批注偏移換算時減去。 */
private const val INDENT = "　　"

/** 段與段之間的行間距（分頁計算與渲染共用同一常量）。 */
private val CHUNK_SPACING = 6.dp

/** 一頁中的文本塊：某段顯示文本（含縮進）的行級切片。 */
data class PageChunk(
    val paraIndex: Int,
    val text: String,
    val dispStart: Int,
    val paraStart: Boolean,
)

data class ReaderPage(val chunks: List<PageChunk>)

/** 拖曳選中：段 + 顯示座標區間（含首行縮進偏移）。 */
data class SelInfo(
    val para: ReaderViewModel.Para,
    val dispStart: Int,
    val dispEnd: Int,
)

/** 批注/劃線在顯示文本（含縮進）中的區間。 */
private fun annDispRange(
    a: AnnotationStore.Annotation, para: ReaderViewModel.Para,
): IntRange {
    val s = if (a.selStart >= 0) a.selStart + INDENT.length else 0
    val e = if (a.selEnd > 0) a.selEnd + INDENT.length
    else para.text.length + INDENT.length
    return s until e
}

/** 顯示座標 → 原文座標（去縮進、夾取到段長）。 */
private fun SelInfo.origRange(): Pair<Int, Int> {
    val s = (dispStart - INDENT.length).coerceIn(0, para.text.length)
    val e = (dispEnd - INDENT.length).coerceIn(0, para.text.length)
    return s to e
}

class ReaderViewModel(
    private val container: AppContainer,
    private val titleOrId: String,
    private val initialSection: String,
    private val locateText: String,
) : ViewModel() {

    data class Para(val index: Int, val text: String)

    data class UiState(
        val loaded: Boolean = false,
        val missing: Boolean = false,
        val unit: LibraryStore.Unit_? = null,
        val toc: List<LibraryStore.Toc> = emptyList(),
        val section: String = "",
        val paras: List<Para> = emptyList(),
        val truncated: Boolean = false,
        val offset: Int = 0,
        val totalChars: Int = 0,
        val fontSize: Int = 18,
        val themeKey: String = "paper",
        val simplified: Boolean = true,
        val annotations: List<AnnotationStore.Annotation> = emptyList(),
        val targetPara: Int? = null,     // 定位開卷的目標段（跳頁+閃亮）
        val resumed: Boolean = false,    // 本次打開恢復了上次閱讀位置
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    companion object {
        // v1.6 翻頁式閱讀：整章一次載入分頁（超長章節分窗續載）
        private const val WINDOW = 400_000
    }

    init {
        viewModelScope.launch {
            val s = container.settings.current()
            val u = container.libraryStore.findByTitle(titleOrId)
            if (u == null) {
                _state.value = _state.value.copy(loaded = true, missing = true,
                    simplified = s.simplifiedDisplay)
                return@launch
            }
            container.settings.pushLibraryRecent(u.id)
            _state.value = _state.value.copy(
                unit = u,
                toc = container.libraryStore.toc(u.id),
                fontSize = s.readerFontSize,
                themeKey = s.readerTheme,
                simplified = s.simplifiedDisplay,
                annotations = container.annotationStore.forBook(u.id),
            )
            // 定位開卷（條文關係 → 直達包含該條文的段落；v1.5 #1）
            if (locateText.isNotBlank()) {
                val loc = container.libraryStore.locate(
                    u.id, locateText.take(14))
                if (loc != null) {
                    open(loc.section, target = loc.paraIndex)
                    return@launch
                }
            }
            // 續讀（v1.9.1）：無顯式章節/定位時恢復上次進度——
            // 舊版每次點開都從頭讀，進度只存在「最近閱讀」的順序裡
            if (initialSection.isBlank()) {
                val prog = container.readingProgress.get(u.id)
                if (prog != null &&
                    (prog.section.isNotBlank() || prog.paraIndex > 0)
                ) {
                    _state.value = _state.value.copy(resumed = true)
                    open(prog.section,
                        target = prog.paraIndex.takeIf { it > 0 })
                    return@launch
                }
            }
            open(initialSection)
        }
    }

    /** 翻頁即記進度（書·章節·段序）；段序對字號/簡繁重排穩定。 */
    fun saveProgress(paraIndex: Int) {
        val u = _state.value.unit ?: return
        val st = _state.value
        viewModelScope.launch {
            container.readingProgress.save(
                org.impfai.hermes.engine.ReadingProgressStore.Progress(
                    bookId = u.id,
                    section = st.section,
                    paraIndex = paraIndex,
                    percent = if (st.paras.isEmpty()) 0f
                    else ((paraIndex + 1).toFloat() / st.paras.size)
                        .coerceIn(0f, 1f),
                    updatedAt = System.currentTimeMillis(),
                ))
        }
    }

    fun clearResumed() {
        _state.value = _state.value.copy(resumed = false)
    }

    fun open(section: String, target: Int? = null) {
        val u = _state.value.unit ?: return
        viewModelScope.launch {
            val r = container.libraryStore.read(u.id, section, 0,
                maxChars = WINDOW)
            _state.value = _state.value.copy(
                loaded = true, section = section,
                paras = splitParas(r.text, 0),
                truncated = r.truncated, offset = WINDOW,
                totalChars = r.total, targetPara = target)
        }
    }

    fun clearTarget() {
        _state.value = _state.value.copy(targetPara = null)
    }

    private var loadingMore = false

    fun loadMore() = viewModelScope.launch {
        if (loadingMore) return@launch
        loadingMore = true
        try {
            val u = _state.value.unit ?: return@launch
            val st = _state.value
            val r = container.libraryStore.read(u.id, st.section, st.offset,
                maxChars = WINDOW)
            _state.value = st.copy(
                paras = st.paras + splitParas(r.text, st.paras.size),
                truncated = r.truncated, offset = st.offset + WINDOW,
                totalChars = r.total)
        } finally {
            loadingMore = false
        }
    }

    private fun splitParas(text: String, baseIndex: Int): List<Para> =
        text.lines().map { it.trim() }.filter { it.isNotBlank() }
            .mapIndexed { i, t -> Para(baseIndex + i, t) }

    fun setFont(size: Int) {
        viewModelScope.launch {
            val v = size.coerceIn(14, 26)
            container.settings.setReaderPrefs(fontSize = v)
            _state.value = _state.value.copy(fontSize = v)
        }
    }

    fun setTheme(key: String) {
        viewModelScope.launch {
            container.settings.setReaderPrefs(theme = key)
            _state.value = _state.value.copy(themeKey = key)
        }
    }

    /** 簡繁切換（v1.6 #4）：全局顯示層設置，原文始終以繁體存儲。 */
    fun setSimplified(on: Boolean) {
        viewModelScope.launch {
            container.settings.setSimplifiedDisplay(on)
            _state.value = _state.value.copy(simplified = on)
        }
    }

    fun annotate(para: Para, kind: AnnotationStore.Kind, note: String = "",
                 selStart: Int = -1, selEnd: Int = -1) {
        val u = _state.value.unit ?: return
        viewModelScope.launch {
            val excerpt = if (selStart in 0 until selEnd &&
                selEnd <= para.text.length
            ) para.text.substring(selStart, selEnd) else para.text
            container.annotationStore.add(
                bookId = u.id, bookTitle = u.title,
                section = _state.value.section, paraIndex = para.index,
                excerpt = excerpt, kind = kind, note = note,
                selStart = selStart, selEnd = selEnd)
            _state.value = _state.value.copy(
                annotations = container.annotationStore.forBook(u.id))
        }
    }

    fun removeAnnotation(id: String) {
        val u = _state.value.unit ?: return
        viewModelScope.launch {
            container.annotationStore.remove(id)
            _state.value = _state.value.copy(
                annotations = container.annotationStore.forBook(u.id))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    titleOrId: String,
    initialSection: String,
    locateText: String = "",
    onBack: () -> Unit,
    /** 長按選句 →「AI 解读」：帶書名+選中原文跳智能體（空實現向後兼容）。 */
    onAskAgent: (question: String) -> Unit = {},
) {
    val container = rememberContainer()
    val vm: ReaderViewModel = viewModel(
        key = "reader-$titleOrId-$initialSection-${locateText.hashCode()}") {
        ReaderViewModel(container, titleOrId, initialSection, locateText)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val theme = READER_THEMES.firstOrNull { it.key == state.themeKey }
        ?: READER_THEMES.first()
    val clipboard = LocalClipboardManager.current

    // —— 翻頁狀態（v1.6 #3：左右滑動翻頁）——
    var pages by remember { mutableStateOf(listOf<ReaderPage>()) }
    var paginating by remember { mutableStateOf(false) }
    var contentKey by remember { mutableStateOf("") }
    val pagerState = rememberPagerState { pages.size.coerceAtLeast(1) }

    // —— 選中/批注狀態（v1.6 #3：原文長按拖曳劃線）——
    var sel by remember { mutableStateOf<SelInfo?>(null) }
    var noteFor by remember { mutableStateOf<SelInfo?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    var viewNote by remember {
        mutableStateOf<AnnotationStore.Annotation?>(null)
    }
    var flashPara by remember { mutableStateOf<Int?>(null) }

    var showToc by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showAa by remember { mutableStateOf(false) }

    // 翻頁即取消選中
    LaunchedEffect(pagerState.currentPage) { sel = null }

    // 閱讀進度自動保存：翻頁即記錄，下次打開該書自動續讀
    var lastSavedPara by remember { mutableStateOf(-1) }
    LaunchedEffect(pagerState.currentPage, pages.size) {
        val p = pages.getOrNull(pagerState.currentPage)
            ?.chunks?.firstOrNull()?.paraIndex ?: return@LaunchedEffect
        if (p != lastSavedPara) {
            lastSavedPara = p
            vm.saveProgress(p)
        }
    }

    // 續讀提示（一次性）
    val toastContext = LocalContext.current
    LaunchedEffect(state.resumed) {
        if (state.resumed) {
            android.widget.Toast.makeText(toastContext,
                "已恢复上次阅读位置", android.widget.Toast.LENGTH_SHORT).show()
            vm.clearResumed()
        }
    }

    // 定位開卷：跳到包含目標段的頁並短暫高亮
    LaunchedEffect(state.targetPara, pages.size) {
        val t = state.targetPara ?: return@LaunchedEffect
        val idx = pages.indexOfFirst { p ->
            p.chunks.any { it.paraIndex == t }
        }
        if (idx >= 0) {
            pagerState.scrollToPage(idx)
            flashPara = t
            vm.clearTarget()
            delay(2200)
            flashPara = null
        }
    }

    // 超長章節：接近末頁時自動續載下一窗（無感續讀）
    LaunchedEffect(pagerState.currentPage, pages.size, state.truncated,
        paginating) {
        if (state.truncated && !paginating && pages.isNotEmpty() &&
            pagerState.currentPage >= pages.size - 3
        ) {
            vm.loadMore()
        }
    }

    Scaffold(
        containerColor = theme.bg,
        topBar = {
            val curPara = pages.getOrNull(pagerState.currentPage)
                ?.chunks?.firstOrNull()?.paraIndex
            val pageBookmark = state.annotations.firstOrNull {
                it.kind == AnnotationStore.Kind.BOOKMARK.name &&
                    it.section == state.section && it.paraIndex == curPara
            }
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.bg,
                    titleContentColor = theme.fg,
                    navigationIconContentColor = theme.fg,
                    actionIconContentColor = theme.fg,
                ),
                title = {
                    Text((state.unit?.title ?: titleOrId)
                        .display(state.simplified),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val p = curPara?.let { state.paras.getOrNull(it) }
                            ?: return@IconButton
                        if (pageBookmark != null) {
                            vm.removeAnnotation(pageBookmark.id)
                        } else {
                            vm.annotate(p, AnnotationStore.Kind.BOOKMARK)
                        }
                    }) {
                        Icon(if (pageBookmark != null) Icons.Filled.Bookmark
                        else Icons.Filled.BookmarkBorder,
                            contentDescription = "书签")
                    }
                    IconButton(onClick = { showToc = true }) {
                        Icon(Icons.AutoMirrored.Filled.List,
                            contentDescription = "目录")
                    }
                    IconButton(onClick = { showNotes = true }) {
                        Icon(Icons.Filled.Bookmarks, contentDescription = "笔记")
                    }
                    TextButton(onClick = { showAa = true }) {
                        Text("Aa", color = theme.fg, fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
        bottomBar = {
            // 頁脚：進度細線 + 章節 + 第N/M頁（v1.6 頁式進度）
            if (state.loaded && !state.missing) {
                Column(Modifier.background(theme.bg)) {
                    val total = pages.size.coerceAtLeast(1)
                    val cur = pagerState.currentPage.coerceIn(0, total - 1)
                    LinearProgressIndicator(
                        progress = { (cur + 1f) / total },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = theme.accent,
                        trackColor = theme.fg.copy(alpha = 0.08f),
                    )
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.toc.isNotEmpty()) {
                            val tocIdx = state.toc.indexOfFirst {
                                it.title == state.section
                            }
                            IconButton(
                                onClick = {
                                    if (tocIdx > 0) {
                                        vm.open(state.toc[tocIdx - 1].title)
                                    }
                                },
                                enabled = tocIdx > 0,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Filled.ChevronLeft, "上一章",
                                    tint = theme.fg.copy(
                                        alpha = if (tocIdx > 0) 0.6f else 0.2f))
                            }
                            Spacer(Modifier.size(4.dp))
                        }
                        Text(state.section.ifBlank { "全文" }
                            .display(state.simplified),
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.fg.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f), maxLines = 1)
                        Text(
                            if (paginating) "排版中 · 第 ${cur + 1}/$total 页"
                            else "第 ${cur + 1}/$total 页",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.fg.copy(alpha = 0.55f))
                        if (state.toc.isNotEmpty()) {
                            val tocIdx = state.toc.indexOfFirst {
                                it.title == state.section
                            }
                            val hasNext = tocIdx < state.toc.size - 1
                            Spacer(Modifier.size(4.dp))
                            IconButton(
                                onClick = {
                                    if (hasNext) {
                                        vm.open(state.toc[tocIdx + 1].title)
                                    }
                                },
                                enabled = hasNext,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Filled.ChevronRight, "下一章",
                                    tint = theme.fg.copy(
                                        alpha = if (hasNext) 0.6f else 0.2f))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (!state.loaded) {
            Row(Modifier.fillMaxWidth().padding(32.dp),
                horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (state.missing) {
            Column(Modifier.padding(padding).padding(16.dp)) {
                NoticeBar("全库未收录「$titleOrId」或本包未内置古籍库",
                    warning = true)
            }
            return@Scaffold
        }

        val annByPara = state.annotations
            .filter { it.section == state.section }
            .groupBy { it.paraIndex }

        Box(Modifier.fillMaxSize().padding(padding).background(theme.bg)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                // 分頁與渲染必須使用同一 TextStyle（行高固定、去字體内邊距，
                // 保證「行數 × 行高」即塊高，逐行切片零誤差）
                val bodyStyle = remember(state.fontSize, theme) {
                    TextStyle(
                        color = theme.fg,
                        fontSize = state.fontSize.sp,
                        lineHeight = (state.fontSize * 1.85f).sp,
                        fontFamily = FontFamily.Serif,
                        textAlign = TextAlign.Justify,
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.None,
                        ),
                        platformStyle = PlatformTextStyle(
                            includeFontPadding = false),
                    )
                }
                val headerStyle = remember(state.fontSize, theme) {
                    TextStyle(
                        color = theme.fg,
                        fontSize = (state.fontSize + 4).sp,
                        lineHeight = ((state.fontSize + 4) * 1.5f).sp,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.None,
                        ),
                        platformStyle = PlatformTextStyle(
                            includeFontPadding = false),
                    )
                }
                val lineHeightPx = with(density) {
                    (state.fontSize * 1.85f).sp.toPx()
                }
                val spacingPx = with(density) { CHUNK_SPACING.toPx() }
                val contentWidthPx = (constraints.maxWidth -
                    with(density) { 44.dp.roundToPx() }).coerceAtLeast(64)
                val pageHeightPx = constraints.maxHeight -
                    with(density) { 24.dp.toPx() } - 6f
                val headerTitle = state.section.ifBlank {
                    state.unit?.title ?: ""
                }.display(state.simplified)

                // —— 分頁排版（後台增量構建，先出前頁後出全書）——
                LaunchedEffect(state.paras, state.fontSize, state.simplified,
                    contentWidthPx, pageHeightPx.roundToInt(),
                    lineHeightPx.roundToInt()) {
                    if (state.paras.isEmpty()) {
                        pages = emptyList()
                        return@LaunchedEffect
                    }
                    val newKey = "${state.unit?.id}|${state.section}"
                    val anchor = if (newKey == contentKey) {
                        pages.getOrNull(pagerState.currentPage)
                            ?.chunks?.firstOrNull()?.paraIndex
                    } else null
                    contentKey = newKey
                    paginating = true
                    val paras = state.paras
                    val simplified = state.simplified
                    withContext(Dispatchers.Default) {
                        val headerLayout = measurer.measure(
                            AnnotatedString(headerTitle), style = headerStyle,
                            constraints = Constraints(
                                maxWidth = contentWidthPx))
                        val headerPx = headerLayout.size.height +
                            with(density) { 64.dp.toPx() }   // 18+10+22+14
                        val built = ArrayList<ReaderPage>()
                        var cur = ArrayList<PageChunk>()
                        var used = 0f
                        var lastEmit = 0
                        for (para in paras) {
                            yield()
                            val disp = INDENT +
                                para.text.display(simplified)
                            val layout = measurer.measure(
                                AnnotatedString(disp), style = bodyStyle,
                                constraints = Constraints(
                                    maxWidth = contentWidthPx))
                            var line = 0
                            while (line < layout.lineCount) {
                                val cap = if (built.isEmpty())
                                    pageHeightPx - headerPx else pageHeightPx
                                val gap = if (cur.isEmpty()) 0f else spacingPx
                                var fit = ((cap - used - gap) /
                                    lineHeightPx).toInt()
                                if (fit < 1) {
                                    if (cur.isEmpty()) {
                                        fit = 1   // 防零容量死循環（極端小屏）
                                    } else {
                                        built.add(ReaderPage(cur))
                                        cur = ArrayList(); used = 0f
                                        if (built.size - lastEmit >= 6) {
                                            pages = built.toList()
                                            lastEmit = built.size
                                        }
                                        continue
                                    }
                                }
                                val k = minOf(fit, layout.lineCount - line)
                                val cs = layout.getLineStart(line)
                                val ce = layout.getLineEnd(line + k - 1)
                                cur.add(PageChunk(para.index,
                                    disp.substring(cs, ce), cs, line == 0))
                                used += gap + k * lineHeightPx
                                line += k
                            }
                        }
                        if (cur.isNotEmpty()) built.add(ReaderPage(cur))
                        pages = built.toList()
                    }
                    paginating = false
                    // 字號/簡繁/續載重排後回到原閱讀位置
                    if (anchor != null && anchor > 0 &&
                        state.targetPara == null
                    ) {
                        val idx = pages.indexOfFirst { p ->
                            p.chunks.any { it.paraIndex == anchor }
                        }
                        if (idx >= 0) pagerState.scrollToPage(idx)
                    }
                }

                when {
                    pages.isEmpty() && paginating -> Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = theme.accent)
                        Spacer(Modifier.height(10.dp))
                        Text("排版中…", color = theme.fg.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.labelMedium)
                    }
                    pages.isEmpty() -> Text("（本章无内容）",
                        color = theme.fg.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.Center))
                    else -> HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                    ) { pageIdx ->
                        val page = pages.getOrNull(pageIdx)
                            ?: return@HorizontalPager
                        PageView(
                            page = page,
                            isFirst = pageIdx == 0,
                            headerTitle = headerTitle,
                            headerStyle = headerStyle,
                            theme = theme,
                            bodyStyle = bodyStyle,
                            paras = state.paras,
                            annByPara = annByPara,
                            sel = sel,
                            flashPara = flashPara,
                            onSel = { sel = it },
                            onViewNote = { viewNote = it },
                        )
                    }
                }
            }

            // —— 浮動操作條：長按拖曳選中後出現（微信讀書式）——
            sel?.let { s ->
                val (os, oe) = s.origRange()
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            if (oe > os) {
                                clipboard.setText(AnnotatedString(
                                    s.para.text.display(state.simplified)
                                        .substring(os, oe)))
                            }
                            sel = null
                        }) { Text("复制") }
                        TextButton(onClick = {
                            if (oe > os) {
                                vm.annotate(s.para,
                                    AnnotationStore.Kind.HIGHLIGHT,
                                    selStart = os, selEnd = oe)
                            }
                            sel = null
                        }) { Text("🖍 划线") }
                        TextButton(onClick = {
                            if (oe > os) {
                                noteFor = s; noteDraft = ""
                            }
                            sel = null
                        }) { Text("📝 批注") }
                        TextButton(onClick = {
                            if (oe > os) {
                                val text = s.para.text
                                    .display(state.simplified)
                                    .substring(os, oe).take(300)
                                val title = (state.unit?.title ?: titleOrId)
                                    .display(state.simplified)
                                onAskAgent(
                                    "请解读《" + title + "》中的这段原文，" +
                                        "说明其含义、医理与相关经典依据：「" +
                                        text + "」")
                            }
                            sel = null
                        }) { Text("🤖 解读") }
                        TextButton(onClick = { sel = null }) { Text("✕") }
                    }
                }
            }
        }
    }

    // —— 批注輸入（對所選字句）——
    noteFor?.let { t ->
        val (os, oe) = t.origRange()
        AlertDialog(
            onDismissRequest = { noteFor = null },
            title = { Text("写批注") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("「" + t.para.text.display(state.simplified)
                        .substring(os, oe).take(48) +
                        (if (oe - os > 48) "…" else "") + "」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = noteDraft, onValueChange = { noteDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("批注内容…") }, maxLines = 4)
                }
            },
            confirmButton = {
                TextButton(enabled = noteDraft.isNotBlank(), onClick = {
                    vm.annotate(t.para, AnnotationStore.Kind.NOTE, noteDraft,
                        selStart = os, selEnd = oe)
                    noteFor = null; noteDraft = ""
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { noteFor = null }) { Text("取消") }
            },
        )
    }

    // —— 查看批注（點按帶批注的字句）——
    viewNote?.let { a ->
        AlertDialog(
            onDismissRequest = { viewNote = null },
            title = { Text("📝 批注") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("「${a.excerpt.display(state.simplified).take(80)}」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(a.note, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewNote = null }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.removeAnnotation(a.id); viewNote = null
                }) { Text("删除") }
            },
        )
    }

    // —— 目錄 ——
    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            LazyColumn(Modifier.padding(horizontal = 16.dp)) {
                item {
                    Text("目录（${state.toc.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                items(state.toc.size) { i ->
                    val t = state.toc[i]
                    Text("　".repeat((t.level - 1).coerceIn(0, 3)) +
                        t.title.display(state.simplified),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                            .combinedClickable(onClick = {
                                vm.open(t.title); showToc = false
                            })
                            .padding(vertical = 8.dp))
                }
                if (state.toc.isEmpty()) {
                    item { Text("（本书无小节标题，直接连续阅读）") }
                }
            }
        }
    }

    // —— 筆記與劃線 ——
    if (showNotes) {
        ModalBottomSheet(onDismissRequest = { showNotes = false }) {
            LazyColumn(Modifier.padding(horizontal = 16.dp)) {
                item {
                    Text("本书笔记 · 划线 · 书签（${state.annotations.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                items(state.annotations.size) { i ->
                    val a = state.annotations[i]
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)
                                .combinedClickable(onClick = {
                                    vm.open(a.section, target = a.paraIndex)
                                    showNotes = false
                                })) {
                                Text(when (a.kind) {
                                    "NOTE" -> "📝 批注"
                                    "BOOKMARK" -> "🔖 书签"
                                    else -> "🖍 划线"
                                } + (a.section.takeIf { it.isNotBlank() }
                                    ?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(a.excerpt,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2)
                                if (a.note.isNotBlank()) {
                                    Text(a.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold)
                                }
                            }
                            IconButton(onClick = { vm.removeAnnotation(a.id) }) {
                                Icon(Icons.Filled.Delete,
                                    contentDescription = "删除")
                            }
                        }
                    }
                }
                if (state.annotations.isEmpty()) {
                    item { Text("（长按正文并拖动手指即可划线、批注；" +
                        "顶栏 🔖 一键加书签）") }
                }
            }
        }
    }

    // —— Aa：字號 / 背景 / 簡繁 ——
    if (showAa) {
        ModalBottomSheet(onDismissRequest = { showAa = false }) {
            Column(Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var fontDraft by remember(state.fontSize) {
                    mutableStateOf(state.fontSize.toFloat())
                }
                Text("字号：${fontDraft.toInt()}sp",
                    style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = fontDraft,
                    onValueChange = { fontDraft = it },
                    onValueChangeFinished = { vm.setFont(fontDraft.toInt()) },
                    valueRange = 14f..26f, steps = 11)
                Text("背景", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    READER_THEMES.forEach { t ->
                        FilterChip(selected = state.themeKey == t.key,
                            onClick = { vm.setTheme(t.key) },
                            label = { Text(t.label) },
                            leadingIcon = {
                                Box(Modifier.size(14.dp)
                                    .background(t.bg, CircleShape))
                            })
                    }
                }
                Text("字体（v1.6 简繁切换）",
                    style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.simplified,
                        onClick = { vm.setSimplified(true) },
                        label = { Text("简体") })
                    FilterChip(selected = !state.simplified,
                        onClick = { vm.setSimplified(false) },
                        label = { Text("繁体（原文）") })
                }
                Text("左右滑动翻页 · 长按正文并拖动手指选字划线",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 單頁：章首飾題（僅第一頁）+ 段塊序列（高度與分頁預算嚴格一致）。 */
@Composable
private fun PageView(
    page: ReaderPage,
    isFirst: Boolean,
    headerTitle: String,
    headerStyle: TextStyle,
    theme: ReaderTheme,
    bodyStyle: TextStyle,
    paras: List<ReaderViewModel.Para>,
    annByPara: Map<Int, List<AnnotationStore.Annotation>>,
    sel: SelInfo?,
    flashPara: Int?,
    onSel: (SelInfo?) -> Unit,
    onViewNote: (AnnotationStore.Annotation) -> Unit,
) {
    Column(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onSel(null) } }
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        if (isFirst) {
            Spacer(Modifier.height(18.dp))
            Text(headerTitle, style = headerStyle,
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).height(22.dp),
                verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f),
                    color = theme.accent.copy(alpha = 0.35f))
                Box(Modifier.padding(horizontal = 8.dp).size(6.dp)
                    .background(theme.accent, CircleShape))
                HorizontalDivider(Modifier.weight(1f),
                    color = theme.accent.copy(alpha = 0.35f))
            }
            Spacer(Modifier.height(14.dp))
        }
        page.chunks.forEachIndexed { i, chunk ->
            if (i > 0) Spacer(Modifier.height(CHUNK_SPACING))
            val para = paras.getOrNull(chunk.paraIndex)
                ?: return@forEachIndexed
            ChunkText(
                chunk = chunk, para = para,
                anns = annByPara[chunk.paraIndex].orEmpty(),
                sel = sel, flash = flashPara == chunk.paraIndex,
                theme = theme, style = bodyStyle,
                onSel = onSel, onViewNote = onViewNote,
            )
        }
    }
}

/** 段塊正文：直接在原文上長按拖曳選字（微信讀書式），
 *  劃線/批注隨文着色，點按批注字句查看內容。 */
@Composable
private fun ChunkText(
    chunk: PageChunk,
    para: ReaderViewModel.Para,
    anns: List<AnnotationStore.Annotation>,
    sel: SelInfo?,
    flash: Boolean,
    theme: ReaderTheme,
    style: TextStyle,
    onSel: (SelInfo?) -> Unit,
    onViewNote: (AnnotationStore.Annotation) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val layoutRef = remember(chunk) {
        mutableStateOf<TextLayoutResult?>(null)
    }
    val chunkEnd = chunk.dispStart + chunk.text.length
    val annotated = remember(chunk, anns, sel, theme) {
        buildAnnotatedString {
            append(chunk.text)
            for (a in anns) {
                if (a.kind == AnnotationStore.Kind.BOOKMARK.name) continue
                val r = annDispRange(a, para)
                val ls = maxOf(r.first, chunk.dispStart) - chunk.dispStart
                val le = minOf(r.last + 1, chunkEnd) - chunk.dispStart
                if (ls < le) {
                    if (a.kind == AnnotationStore.Kind.NOTE.name) {
                        addStyle(SpanStyle(
                            background = theme.highlight.copy(alpha = 0.25f),
                            textDecoration = TextDecoration.Underline,
                        ), ls, le)
                    } else {
                        addStyle(SpanStyle(background = theme.highlight),
                            ls, le)
                    }
                }
            }
            if (sel != null && sel.para.index == para.index) {
                val ls = maxOf(sel.dispStart, chunk.dispStart) -
                    chunk.dispStart
                val le = minOf(sel.dispEnd, chunkEnd) - chunk.dispStart
                if (ls < le) {
                    addStyle(SpanStyle(
                        background = theme.accent.copy(alpha = 0.30f)),
                        ls, le)
                }
            }
        }
    }
    Text(
        annotated,
        style = style,
        onTextLayout = { layoutRef.value = it },
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (flash) theme.highlight else Color.Transparent,
                RoundedCornerShape(4.dp))
            .pointerInput(chunk) {
                var anchor = 0
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        haptic.performHapticFeedback(
                            HapticFeedbackType.LongPress)
                        val l = layoutRef.value
                            ?: return@detectDragGesturesAfterLongPress
                        anchor = l.getOffsetForPosition(pos)
                        onSel(SelInfo(para,
                            chunk.dispStart + anchor,
                            chunk.dispStart + (anchor + 1)
                                .coerceAtMost(chunk.text.length)))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val l = layoutRef.value ?: return@detectDragGesturesAfterLongPress
                        val off = l.getOffsetForPosition(change.position)
                        val s = minOf(anchor, off)
                        val e = (maxOf(anchor, off) + 1)
                            .coerceAtMost(chunk.text.length)
                        if (s < e) {
                            onSel(SelInfo(para,
                                chunk.dispStart + s, chunk.dispStart + e))
                        }
                    },
                )
            }
            .pointerInput(chunk, anns) {
                detectTapGestures { pos ->
                    val off = layoutRef.value?.getOffsetForPosition(pos)
                    val hit = if (off != null) anns.firstOrNull { a ->
                        a.kind == AnnotationStore.Kind.NOTE.name &&
                            a.note.isNotBlank() &&
                            (chunk.dispStart + off) in annDispRange(a, para)
                    } else null
                    if (hit != null) onViewNote(hit) else onSel(null)
                }
            },
    )
}
