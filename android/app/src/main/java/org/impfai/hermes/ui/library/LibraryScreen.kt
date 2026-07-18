package org.impfai.hermes.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import org.impfai.hermes.engine.LibraryStore
import org.impfai.hermes.ui.common.NoticeBar
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer
import org.impfai.hermes.ui.features.FeatureScaffold

/**
 * 古籍庫書架（v1.4 Kindle 式）：收藏置頂 + 最近閱讀 + 分類書架 +
 * 全文檢索。狀態全部在 ViewModel（backstack 存續）——從閱讀器返回
 * 時檢索結果仍在（修復 v1.3 返回清零）。
 */
class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    data class UiState(
        val ready: Boolean? = null,
        val tab: Int = 0,
        val simplified: Boolean = true,
        val nBooks: Int = 0,
        val categories: Map<String, Int> = emptyMap(),
        val query: String = "",
        val category: String = "",
        val results: List<LibraryStore.Unit_> = emptyList(),
        val favorites: Set<String> = emptySet(),
        val recents: List<LibraryStore.Unit_> = emptyList(),
        val grepQuery: String = "",
        val grepHits: List<LibraryStore.GrepHit> = emptyList(),
        val grepRunning: Boolean = false,
        val grepProgress: Float = 0f,
        val grepSearched: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val s = container.settings.current()
            val ok = container.libraryStore.ensureCatalog()
            if (!ok) {
                _state.value = _state.value.copy(
                    ready = false, simplified = s.simplifiedDisplay)
                return@launch
            }
            val (nBooks, _, cats) = container.libraryStore.stats()
            _state.value = _state.value.copy(
                ready = true, simplified = s.simplifiedDisplay,
                nBooks = nBooks, categories = cats,
                favorites = s.libraryFavorites,
            )
            refreshShelf()
        }
    }

    /** 書架排序：收藏 → 體量大的經典。 */
    private suspend fun refreshShelf() {
        val st = _state.value
        val s = container.settings.current()
        val found = container.libraryStore
            .searchCatalog(st.query, st.category, limit = 60)
        val sorted = found.sortedByDescending { it.id in s.libraryFavorites }
        val recents = s.libraryRecents.mapNotNull {
            container.libraryStore.unit(it)
        }
        _state.value = _state.value.copy(
            results = sorted, favorites = s.libraryFavorites, recents = recents)
    }

    fun setTab(i: Int) { _state.value = _state.value.copy(tab = i) }

    fun setQuery(q: String) { _state.value = _state.value.copy(query = q) }

    fun search() = viewModelScope.launch { refreshShelf() }

    fun setCategory(c: String) {
        _state.value = _state.value.copy(
            category = if (_state.value.category == c) "" else c)
        search()
    }

    fun toggleFavorite(bookId: String) {
        viewModelScope.launch {
            container.settings.toggleLibraryFavorite(bookId)
            refreshShelf()
        }
    }

    /** 閱讀返回後刷新最近列表（VM 存續，檢索結果不動）。 */
    fun onResume() = viewModelScope.launch { refreshShelf() }

    fun setGrepQuery(q: String) {
        _state.value = _state.value.copy(grepQuery = q)
    }

    fun grep() {
        val q = _state.value.grepQuery
        if (q.isBlank() || _state.value.grepRunning) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                grepRunning = true, grepSearched = false)
            val hits = container.libraryStore.grep(q) { done, total ->
                _state.value = _state.value.copy(
                    grepProgress = if (total == 0) 1f
                    else done.toFloat() / total)
            }
            _state.value = _state.value.copy(
                grepHits = hits, grepRunning = false, grepSearched = true)
        }
    }
}

/**
 * 古籍裝幀配色（外部需求：書架封面要有中國古代設計感，分類各異）。
 *
 * 視覺原型是清代線裝書：布面書衣 + 左側裝訂線（四針眼）+ 竪排題簽
 * （書名紙簽）+ 朱文小印（分類印章）。顏色按分類分八系，取傳統
 * 礦植物色（赭石/竹月/藏青/紫檀/胭脂/黛青/絳紅/駝褐），飽和度壓低
 * 貼近舊書質感。
 */
private data class CoverStyle(
    val cloth: Color,       // 書衣主色
    val clothDark: Color,   // 書衣暗部（漸變/裝訂邊）
)

private val COVER_PAPER = Color(0xFFF5EDD8)   // 題簽紙
private val COVER_INK = Color(0xFF2A241C)     // 題簽墨字
private val COVER_SEAL = Color(0xFFA63B2A)    // 朱文印

private val STYLE_CLASSIC = CoverStyle(Color(0xFF7A5230), Color(0xFF573A1E))  // 赭石·經典
private val STYLE_HERB = CoverStyle(Color(0xFF3E5C41), Color(0xFF2A4030))     // 竹月·本草
private val STYLE_FORMULA = CoverStyle(Color(0xFF35507A), Color(0xFF223A5C)) // 藏青·方書
private val STYLE_CASE = CoverStyle(Color(0xFF5C4059), Color(0xFF423043))     // 紫檀·醫案
private val STYLE_WOMEN = CoverStyle(Color(0xFF8C4A52), Color(0xFF663036))    // 胭脂·婦兒
private val STYLE_NEEDLE = CoverStyle(Color(0xFF3D5A66), Color(0xFF28424E))   // 黛青·針診
private val STYLE_CLINIC = CoverStyle(Color(0xFF7A4A38), Color(0xFF573224))   // 絳紅·臨證
private val STYLE_MISC = CoverStyle(Color(0xFF5C564A), Color(0xFF423E34))     // 駝褐·雜纂

private fun coverStyle(category: String): CoverStyle {
    fun hit(vararg keys: String) = keys.any { it in category }
    return when {
        hit("傷寒", "金匱", "內經", "難經", "經論") -> STYLE_CLASSIC
        hit("本草", "炮製", "養生") -> STYLE_HERB
        hit("方書", "綜合") -> STYLE_FORMULA
        hit("醫案", "醫論") -> STYLE_CASE
        hit("婦科", "兒科") -> STYLE_WOMEN
        hit("針灸", "經穴", "脈法", "診法", "診治") -> STYLE_NEEDLE
        hit("溫病", "內科", "外科", "傷科", "喉科", "眼科", "五官", "齒科") ->
            STYLE_CLINIC
        else -> STYLE_MISC
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenBook: (bookId: String, section: String) -> Unit,
) {
    val container = rememberContainer()
    val vm: LibraryViewModel = viewModel { LibraryViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    // 從閱讀器返回：刷新收藏/最近（不清檢索狀態）
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.onResume() }

    FeatureScaffold("古籍库 · 书架", onBack) { padding ->
        when (state.ready) {
            null -> Row(Modifier.fillMaxWidth().padding(32.dp),
                horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            false -> Column(Modifier.padding(padding).padding(16.dp)) {
                NoticeBar("全量古籍库未内置本包（轻量版）。请安装 VIP-full 版" +
                    "使用全量古籍库，或连接 Hermes 服务端。", warning = true)
            }
            true -> Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = state.tab) {
                    Tab(state.tab == 0, onClick = { vm.setTab(0) },
                        text = { Text("书架") })
                    Tab(state.tab == 1, onClick = { vm.setTab(1) },
                        text = { Text("全文检索") })
                }
                when (state.tab) {
                    0 -> Bookshelf(vm, state, onOpenBook)
                    1 -> GrepTab(vm, state, onOpenBook)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bookshelf(
    vm: LibraryViewModel,
    state: LibraryViewModel.UiState,
    onOpenBook: (String, String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "中医笈成全库 · 收藏 ${state.favorites.size}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("检索书名 / 作者 / 朝代 / 分类（简繁均可）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.categories.entries.take(10).forEach { (cat, n) ->
                    FilterChip(selected = state.category == cat,
                        onClick = { vm.setCategory(cat) },
                        label = { Text("${cat.display(state.simplified)} $n") })
                }
            }
        }
        if (state.recents.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("继续阅读", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.recents.forEach { u ->
                            BookCover(u, state, vm, compact = true) {
                                onOpenBook(u.id, "")
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                if (state.favorites.isEmpty()) "全部书籍（点 ☆ 收藏置顶）"
                else "书架（收藏置顶）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.results.forEach { u ->
                    BookCover(u, state, vm) { onOpenBook(u.id, "") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 線裝書封：布面書衣（分類色系）+ 左裝訂線與四針眼 + 竪排題簽書名 +
 * 朱文分類印 + 收藏星標；簽下小字為朝代·作者。
 */
@Composable
private fun BookCover(
    u: LibraryStore.Unit_,
    state: LibraryViewModel.UiState,
    vm: LibraryViewModel,
    compact: Boolean = false,
    onOpen: () -> Unit,
) {
    val w = if (compact) 88.dp else 100.dp
    val h = if (compact) 118.dp else 136.dp
    val style = coverStyle(u.category)
    val titleChars = u.title.display(state.simplified)
        .filter { !it.isWhitespace() }.take(8)
    val sealChar = u.category.display(state.simplified)
        .firstOrNull { !it.isWhitespace() } ?: '醫'
    Column(Modifier.width(w)) {
        Box(
            Modifier
                .width(w).height(h)
                .background(
                    Brush.horizontalGradient(
                        listOf(style.clothDark, style.cloth, style.cloth)),
                    RoundedCornerShape(4.dp))
                .clickable { onOpen() },
        ) {
            // 裝訂邊：暗色細條 + 四針眼（線裝視覺）
            Box(
                Modifier.align(Alignment.CenterStart)
                    .width(9.dp).height(h)
                    .background(style.clothDark,
                        RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    repeat(4) {
                        Box(
                            Modifier.width(3.dp).height(3.dp)
                                .background(COVER_PAPER.copy(alpha = 0.75f),
                                    RoundedCornerShape(2.dp)))
                    }
                }
            }
            // 竪排題簽（書名紙簽，仿古籍簽條）
            Column(
                Modifier.align(Alignment.TopStart)
                    .padding(start = 15.dp, top = 7.dp)
                    .width(if (compact) 24.dp else 28.dp)
                    .background(COVER_PAPER, RoundedCornerShape(2.dp))
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                titleChars.forEach { ch ->
                    Text(
                        ch.toString(),
                        color = COVER_INK,
                        fontSize = if (compact) 11.sp else 12.sp,
                        lineHeight = if (compact) 12.sp else 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            // 朱文分類印（不同分類印文不同）
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 6.dp)
                    .width(20.dp).height(20.dp)
                    .background(COVER_SEAL, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(sealChar.toString(), color = COVER_PAPER,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            // 朝代（書衣右上小字，簽外）
            if (u.dynasty.isNotBlank()) {
                Text(
                    u.dynasty.display(state.simplified).take(2),
                    color = COVER_PAPER.copy(alpha = 0.65f),
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(top = 40.dp, end = 9.dp),
                )
            }
            IconButton(
                onClick = { vm.toggleFavorite(u.id) },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    if (u.id in state.favorites) Icons.Filled.Star
                    else Icons.Filled.StarBorder,
                    contentDescription = "收藏",
                    tint = if (u.id in state.favorites) Color(0xFFF3CE56)
                    else Color(0x66FFFFFF),
                )
            }
        }
        Text(
            listOf(u.category, u.author).filter { it.isNotBlank() }
                .joinToString(" · ").display(state.simplified),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GrepTab(
    vm: LibraryViewModel,
    state: LibraryViewModel.UiState,
    onOpenBook: (String, String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.grepQuery,
                onValueChange = vm::setGrepQuery,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("全库原文检索，如：奔豚 / 四逆汤") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.grep() }),
            )
        }
        item {
            Text("稀字倒排索引剪枝候选书 → 逐书流式验证（与服务端同算法）；" +
                "打开结果后返回，本页检索结果保留",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.grepRunning) {
            item {
                LinearProgressIndicator(progress = { state.grepProgress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
        }
        if (state.grepSearched && state.grepHits.isEmpty()) {
            item { NoticeBar("全库未检得该词") }
        }
        items(state.grepHits.size) { i ->
            val h = state.grepHits[i]
            Card(Modifier.fillMaxWidth()
                .clickable { onOpenBook(h.unit.id, h.section) }) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        (h.unit.title + (h.section.takeIf { it.isNotBlank() }
                            ?.let { " · $it" } ?: ""))
                            .display(state.simplified),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text("…${h.excerpt.display(state.simplified)}…",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
