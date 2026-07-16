package org.impfai.hermes.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val bg: Color, val fg: Color, val highlight: Color,
)

val READER_THEMES = listOf(
    ReaderTheme("paper", "米黄", Color(0xFFFAF3E3), Color(0xFF3B3226),
        Color(0x59E8C96A)),
    ReaderTheme("white", "纯白", Color(0xFFFFFFFF), Color(0xFF202320),
        Color(0x4DFFD54F)),
    ReaderTheme("green", "豆沙绿", Color(0xFFCCE8CF), Color(0xFF23392B),
        Color(0x59A9D18E)),
    ReaderTheme("night", "夜间", Color(0xFF15130F), Color(0xFFD8CFBE),
        Color(0x40E8C96A)),
)

class ReaderViewModel(
    private val container: AppContainer,
    private val titleOrId: String,
    private val initialSection: String,
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
        val fontSize: Int = 18,
        val themeKey: String = "paper",
        val simplified: Boolean = true,
        val annotations: List<AnnotationStore.Annotation> = emptyList(),
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
            open(initialSection)
        }
    }

    fun open(section: String) {
        val u = _state.value.unit ?: return
        viewModelScope.launch {
            val r = container.libraryStore.read(u.id, section, 0)
            _state.value = _state.value.copy(
                loaded = true, section = section,
                paras = splitParas(r.text, 0),
                truncated = r.truncated, offset = 4000)
        }
    }

    fun loadMore() {
        val u = _state.value.unit ?: return
        val st = _state.value
        viewModelScope.launch {
            val r = container.libraryStore.read(u.id, st.section, st.offset)
            _state.value = st.copy(
                paras = st.paras + splitParas(r.text, st.paras.size),
                truncated = r.truncated, offset = st.offset + 4000)
        }
    }

    private fun splitParas(text: String, baseIndex: Int): List<Para> =
        text.lines().map { it.trim() }.filter { it.isNotBlank() }
            .mapIndexed { i, t -> Para(baseIndex + i, t) }

    fun setFont(delta: Int) {
        viewModelScope.launch {
            val size = (_state.value.fontSize + delta).coerceIn(14, 26)
            container.settings.setReaderPrefs(fontSize = size)
            _state.value = _state.value.copy(fontSize = size)
        }
    }

    fun setTheme(key: String) {
        viewModelScope.launch {
            container.settings.setReaderPrefs(theme = key)
            _state.value = _state.value.copy(themeKey = key)
        }
    }

    fun annotate(para: Para, kind: AnnotationStore.Kind, note: String = "") {
        val u = _state.value.unit ?: return
        viewModelScope.launch {
            container.annotationStore.add(
                bookId = u.id, bookTitle = u.title,
                section = _state.value.section, paraIndex = para.index,
                excerpt = para.text, kind = kind, note = note)
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
    onBack: () -> Unit,
) {
    val container = rememberContainer()
    val vm: ReaderViewModel = viewModel(key = "reader-$titleOrId-$initialSection") {
        ReaderViewModel(container, titleOrId, initialSection)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val theme = READER_THEMES.firstOrNull { it.key == state.themeKey }
        ?: READER_THEMES.first()
    val clipboard = LocalClipboardManager.current

    var showToc by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showAa by remember { mutableStateOf(false) }
    var menuPara by remember { mutableStateOf<ReaderViewModel.Para?>(null) }
    var noteDraft by remember { mutableStateOf("") }

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
                    Text(
                        (state.unit?.title ?: titleOrId).display(state.simplified),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
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
                        Text("Aa", color = theme.fg,
                            fontWeight = FontWeight.Bold)
                    }
                },
            )
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
                NoticeBar("全库未收录「$titleOrId」或本包未内置古籍库", warning = true)
            }
            return@Scaffold
        }

        val annByPara = state.annotations
            .filter { it.section == state.section }
            .groupBy { it.paraIndex }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding)
                .background(theme.bg)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    (state.section.ifBlank { "全文" }).display(state.simplified),
                    style = MaterialTheme.typography.labelLarge,
                    color = theme.fg.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.paras.size, key = { state.paras[it].index }) { i ->
                val para = state.paras[i]
                val anns = annByPara[para.index].orEmpty()
                val highlighted = anns.any {
                    it.kind == AnnotationStore.Kind.HIGHLIGHT.name ||
                        it.kind == AnnotationStore.Kind.NOTE.name
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (highlighted) theme.highlight
                            else Color.Transparent,
                            RoundedCornerShape(4.dp))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { menuPara = para; noteDraft = "" },
                        ),
                ) {
                    Text(
                        para.text.display(state.simplified),
                        color = theme.fg,
                        fontSize = state.fontSize.sp,
                        lineHeight = (state.fontSize * 1.75f).sp,
                        fontFamily = FontFamily.Serif,
                    )
                    anns.filter { it.note.isNotBlank() }.forEach { a ->
                        Text("📝 ${a.note}",
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.fg.copy(alpha = 0.75f),
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
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
                Text("长按段落：划线 / 批注 / 书签 / 复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.fg.copy(alpha = 0.45f),
                    modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }

    // —— 段落操作 ——
    menuPara?.let { para ->
        ModalBottomSheet(onDismissRequest = { menuPara = null }) {
            Column(Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(para.text.take(60) + if (para.text.length > 60) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.annotate(para, AnnotationStore.Kind.HIGHLIGHT)
                        menuPara = null
                    }) { Text("划线") }
                    OutlinedButton(onClick = {
                        vm.annotate(para, AnnotationStore.Kind.BOOKMARK)
                        menuPara = null
                    }) { Text("加书签") }
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(para.text))
                        menuPara = null
                    }) { Text("复制") }
                }
                OutlinedTextField(
                    value = noteDraft, onValueChange = { noteDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("写批注…") },
                    maxLines = 3,
                )
                Button(
                    onClick = {
                        vm.annotate(para, AnnotationStore.Kind.NOTE, noteDraft)
                        menuPara = null
                    },
                    enabled = noteDraft.isNotBlank(),
                ) { Text("保存批注") }
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
                    Text(
                        "　".repeat((t.level - 1).coerceIn(0, 3)) +
                            t.title.display(state.simplified),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                            .combinedClickable(onClick = {
                                vm.open(t.title); showToc = false
                            })
                            .padding(vertical = 8.dp),
                    )
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
                            verticalAlignment = androidx.compose.ui.Alignment
                                .CenterVertically) {
                            Column(Modifier.weight(1f)
                                .combinedClickable(onClick = {
                                    vm.open(a.section); showNotes = false
                                })) {
                                Text(
                                    when (a.kind) {
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
                                Icon(Icons.Filled.Delete, contentDescription = "删除")
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
                    onValueChange = { vm.setFont(it.toInt() - state.fontSize) },
                    valueRange = 14f..26f, steps = 11,
                )
                Text("背景", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    READER_THEMES.forEach { t ->
                        FilterChip(
                            selected = state.themeKey == t.key,
                            onClick = { vm.setTheme(t.key) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        }
    }
}
