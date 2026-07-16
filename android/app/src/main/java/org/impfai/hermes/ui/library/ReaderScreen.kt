package org.impfai.hermes.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.impfai.hermes.AppContainer
import org.impfai.hermes.engine.AnnotationStore
import org.impfai.hermes.engine.LibraryStore
import org.impfai.hermes.ui.common.NoticeBar
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer

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
        val targetPara: Int? = null,     // 定位開卷的目標段（滾動+閃亮）
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

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
            open(initialSection)
        }
    }

    fun open(section: String, target: Int? = null) {
        val u = _state.value.unit ?: return
        viewModelScope.launch {
            val r = container.libraryStore.read(u.id, section, 0)
            _state.value = _state.value.copy(
                loaded = true, section = section,
                paras = splitParas(r.text, 0),
                truncated = r.truncated, offset = 4000,
                totalChars = r.total, targetPara = target)
            // 目標段在後續窗口：自動續載直到可見（上限 20 窗）
            var guard = 0
            while (target != null && _state.value.paras.size <= target &&
                _state.value.truncated && guard < 20
            ) {
                loadMoreInternal(); guard++
            }
        }
    }

    fun clearTarget() {
        _state.value = _state.value.copy(targetPara = null)
    }

    fun loadMore() = viewModelScope.launch { loadMoreInternal() }

    private suspend fun loadMoreInternal() {
        val u = _state.value.unit ?: return
        val st = _state.value
        val r = container.libraryStore.read(u.id, st.section, st.offset)
        _state.value = st.copy(
            paras = st.paras + splitParas(r.text, st.paras.size),
            truncated = r.truncated, offset = st.offset + 4000,
            totalChars = r.total)
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
    val listState = rememberLazyListState()

    var showToc by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showAa by remember { mutableStateOf(false) }
    var menuPara by remember { mutableStateOf<ReaderViewModel.Para?>(null) }
    var selectPara by remember { mutableStateOf<ReaderViewModel.Para?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    var flashPara by remember { mutableStateOf<Int?>(null) }

    // 定位開卷：滾動到目標段並短暫高亮
    LaunchedEffect(state.targetPara, state.paras.size) {
        val t = state.targetPara ?: return@LaunchedEffect
        if (state.paras.size > t) {
            listState.animateScrollToItem(t + 1)   // +1：首項為章節題
            flashPara = t
            vm.clearTarget()
            delay(2200)
            flashPara = null
        }
    }

    Scaffold(
        containerColor = theme.bg,
        topBar = {
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
            // 閱讀進度：細線 + 章節/百分比（Kindle 式頁脚）
            if (state.loaded && !state.missing && state.totalChars > 0) {
                Column(Modifier.background(theme.bg)) {
                    val pct = ((state.offset - 4000 + state.paras
                        .sumOf { it.text.length })
                        .coerceAtMost(state.totalChars).toFloat() /
                        state.totalChars).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = theme.accent,
                        trackColor = theme.fg.copy(alpha = 0.08f),
                    )
                    Row(Modifier.fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text(state.section.ifBlank { "全文" }
                            .display(state.simplified),
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.fg.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${(pct * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.fg.copy(alpha = 0.5f))
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

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding)
                .background(theme.bg).padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(
                (state.fontSize * 0.7f).dp),
        ) {
            // 章節題：居中大字 + 紋樣分隔（頂級書卷排版）
            item {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp,
                    bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        state.section.ifBlank {
                            state.unit?.title ?: ""
                        }.display(state.simplified),
                        fontSize = (state.fontSize + 4).sp,
                        lineHeight = ((state.fontSize + 4) * 1.5f).sp,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = theme.fg,
                        textAlign = TextAlign.Center,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 10.dp)) {
                        HorizontalDivider(Modifier.weight(1f),
                            color = theme.accent.copy(alpha = 0.35f))
                        Text("  ❦  ", color = theme.accent, fontSize = 14.sp)
                        HorizontalDivider(Modifier.weight(1f),
                            color = theme.accent.copy(alpha = 0.35f))
                    }
                }
            }
            items(state.paras.size, key = { state.paras[it].index }) { i ->
                val para = state.paras[i]
                val anns = annByPara[para.index].orEmpty()
                val flash = flashPara == para.index
                ParaText(para, anns, theme, state.fontSize,
                    state.simplified, flash,
                    onLongPress = { menuPara = para; noteDraft = "" })
            }
            if (state.truncated) {
                item {
                    OutlinedButton(onClick = vm::loadMore,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("继续阅读")
                    }
                }
            }
            item {
                Text("长按段落：划线 · 批注 · 书签 · 选取字句",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.fg.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .padding(vertical = 14.dp))
            }
        }
    }

    // —— 段落操作 ——
    menuPara?.let { para ->
        ModalBottomSheet(onDismissRequest = { menuPara = null }) {
            Column(Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(para.text.take(60) +
                    if (para.text.length > 60) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.annotate(para, AnnotationStore.Kind.HIGHLIGHT)
                        menuPara = null
                    }) { Text("整段划线") }
                    OutlinedButton(onClick = {
                        selectPara = para; menuPara = null
                    }) { Text("选取字句…") }
                    OutlinedButton(onClick = {
                        vm.annotate(para, AnnotationStore.Kind.BOOKMARK)
                        menuPara = null
                    }) { Text("书签") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(para.text))
                        menuPara = null
                    }) { Text("复制整段") }
                }
                OutlinedTextField(
                    value = noteDraft, onValueChange = { noteDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("写批注（整段）…") }, maxLines = 3)
                Button(onClick = {
                    vm.annotate(para, AnnotationStore.Kind.NOTE, noteDraft)
                    menuPara = null
                }, enabled = noteDraft.isNotBlank()) { Text("保存批注") }
            }
        }
    }

    // —— 字句級選取（v1.5 #3）——
    selectPara?.let { para ->
        var tfv by remember(para.index) {
            mutableStateOf(TextFieldValue(para.text))
        }
        var selNote by remember(para.index) { mutableStateOf("") }
        val sel = tfv.selection
        val hasSel = !sel.collapsed
        ModalBottomSheet(onDismissRequest = { selectPara = null }) {
            Column(Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("拖动选择柄选中字句，再划线/批注/复制",
                    style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = tfv, onValueChange = { tfv = it },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                if (hasSel) {
                    Text("已选：「${para.text.substring(
                        sel.min, sel.max).take(40)}」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = hasSel, onClick = {
                        vm.annotate(para, AnnotationStore.Kind.HIGHLIGHT,
                            selStart = sel.min, selEnd = sel.max)
                        selectPara = null
                    }) { Text("划线所选") }
                    OutlinedButton(enabled = hasSel, onClick = {
                        clipboard.setText(AnnotatedString(
                            para.text.substring(sel.min, sel.max)))
                        selectPara = null
                    }) { Text("复制所选") }
                }
                OutlinedTextField(
                    value = selNote, onValueChange = { selNote = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("对所选字句写批注…") }, maxLines = 3)
                Button(enabled = hasSel && selNote.isNotBlank(), onClick = {
                    vm.annotate(para, AnnotationStore.Kind.NOTE, selNote,
                        selStart = sel.min, selEnd = sel.max)
                    selectPara = null
                }) { Text("保存字句批注") }
            }
        }
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
                    item { Text("（长按正文段落即可划线、批注、加书签）") }
                }
            }
        }
    }

    // —— Aa：字號與主題 ——
    if (showAa) {
        ModalBottomSheet(onDismissRequest = { showAa = false }) {
            Column(Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("字号：${state.fontSize}sp",
                    style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = state.fontSize.toFloat(),
                    onValueChange = { vm.setFont(it.toInt()) },
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
            }
        }
    }
}

/** 正文段落：襯線 + 首行縮進二字 + 字句級劃線着色 + 批注隨文。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParaText(
    para: ReaderViewModel.Para,
    anns: List<AnnotationStore.Annotation>,
    theme: ReaderTheme,
    fontSize: Int,
    simplified: Boolean,
    flash: Boolean,
    onLongPress: () -> Unit,
) {
    val display = para.text.display(simplified)
    val annotated: AnnotatedString = buildAnnotatedString {
        append(display)
        for (a in anns) {
            if (a.kind == AnnotationStore.Kind.BOOKMARK.name) continue
            // 簡繁顯示轉換為單字映射，字符偏移在兩空間一致
            val start = if (a.selStart >= 0) a.selStart else 0
            val end = if (a.selEnd > 0) a.selEnd else display.length
            if (start < end && end <= display.length) {
                addStyle(SpanStyle(background = theme.highlight), start, end)
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (flash) theme.highlight else Color.Transparent,
                RoundedCornerShape(4.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
    ) {
        Text(
            annotated,
            color = theme.fg,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.85f).sp,
            fontFamily = FontFamily.Serif,
            style = MaterialTheme.typography.bodyLarge.copy(
                textIndent = TextIndent(firstLine = 2.em),
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.85f).sp,
            ),
        )
        anns.filter { it.note.isNotBlank() }.forEach { a ->
            Text("📝 ${a.note}",
                style = MaterialTheme.typography.bodySmall,
                color = theme.accent,
                modifier = Modifier.padding(top = 2.dp, start = 8.dp))
        }
        if (anns.any { it.kind == AnnotationStore.Kind.BOOKMARK.name }) {
            Text("🔖", modifier = Modifier.padding(start = 8.dp))
        }
    }
}
