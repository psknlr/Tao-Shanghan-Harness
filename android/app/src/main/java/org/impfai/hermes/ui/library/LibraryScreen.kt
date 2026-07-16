package org.impfai.hermes.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.impfai.hermes.engine.LibraryStore
import org.impfai.hermes.ui.common.NoticeBar
import org.impfai.hermes.ui.common.SectionCard
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer
import org.impfai.hermes.ui.features.FeatureScaffold

/** 全量古籍庫：編目檢索 / 全文檢索（稀字剪枝）/ 章節閱讀。 */
@Composable
fun LibraryScreen(onBack: () -> Unit) {
    val container = rememberContainer()
    val store = container.libraryStore
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf<Boolean?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var simplified by remember { mutableStateOf(true) }

    // 閱讀態（進入某書後）
    var openBook by remember { mutableStateOf<LibraryStore.Unit_?>(null) }
    var toc by remember { mutableStateOf<List<LibraryStore.Toc>>(emptyList()) }
    var section by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var offset by remember { mutableIntStateOf(0) }
    var truncated by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        simplified = container.settings.current().simplifiedDisplay
        ready = store.ensureCatalog()
    }

    suspend fun load(book: LibraryStore.Unit_, sec: String, off: Int, append: Boolean) {
        val r = store.read(book.id, sec, off)
        text = if (append) text + r.text else r.text
        truncated = r.truncated
        offset = off + 4000
    }

    FeatureScaffold(
        openBook?.let { it.title.display(simplified) } ?: "全量古籍库（中医笈成）",
        onBack = {
            if (openBook != null) { openBook = null; text = ""; section = "" }
            else onBack()
        },
    ) { padding ->
        when (ready) {
            null -> Row(Modifier.fillMaxWidth().padding(32.dp),
                horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            false -> Column(Modifier.padding(padding).padding(16.dp)) {
                NoticeBar("全量古籍库未内置本包。VIP-full 版 APK 已预装 803 部" +
                    "（约 100MB）；轻量包可连接 Hermes 服务端使用全库。",
                    warning = true)
            }
            true -> {
                val book = openBook
                if (book != null) {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(padding)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text(
                                listOf(book.author, book.dynasty, book.category)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ").display(simplified),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        if (text.isBlank() && toc.isNotEmpty()) {
                            item {
                                Text("目录（${toc.size}）",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold)
                            }
                            items(toc.size) { i ->
                                val t = toc[i]
                                Text(
                                    ("　".repeat((t.level - 1).coerceIn(0, 3))) +
                                        t.title.display(simplified),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        scope.launch {
                                            section = t.title
                                            load(book, t.title, 0, append = false)
                                        }
                                    }.padding(vertical = 6.dp),
                                )
                            }
                            if (toc.isEmpty()) {
                                item { Text("（无目录，直接阅读）") }
                            }
                        }
                        if (text.isNotBlank()) {
                            item {
                                SectionCard(section.ifBlank { "全文" }
                                    .display(simplified)) {
                                    Text(text.display(simplified),
                                        style = MaterialTheme.typography.bodyMedium
                                            .copy(lineHeight = 26.sp))
                                    if (truncated) {
                                        Button(onClick = {
                                            scope.launch {
                                                load(book, section, offset,
                                                    append = true)
                                            }
                                        }) { Text("继续阅读") }
                                    }
                                }
                            }
                        }
                        if (text.isBlank() && toc.isEmpty()) {
                            item {
                                Button(onClick = {
                                    scope.launch { load(book, "", 0, false) }
                                }) { Text("开始阅读") }
                            }
                        }
                    }
                    return@FeatureScaffold
                }

                Column(Modifier.fillMaxSize().padding(padding)) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(tab == 0, onClick = { tab = 0 }, text = { Text("书目") })
                        Tab(tab == 1, onClick = { tab = 1 }, text = { Text("全文检索") })
                    }
                    when (tab) {
                        0 -> CatalogTab(store, simplified) { u ->
                            scope.launch {
                                openBook = u; text = ""; section = ""
                                toc = store.toc(u.id)
                            }
                        }
                        1 -> GrepTab(store, simplified) { u, sec ->
                            scope.launch {
                                openBook = u; toc = store.toc(u.id)
                                section = sec
                                load(u, sec, 0, append = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogTab(
    store: LibraryStore,
    simplified: Boolean,
    onOpen: (LibraryStore.Unit_) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LibraryStore.Unit_>>(emptyList()) }
    val (nBooks, nUnits, categories) = store.stats()

    LaunchedEffect(Unit) { results = store.searchCatalog("", "", limit = 40) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text("书名/作者/朝代/分类（$nBooks 部 · $nUnits 单元）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                scope.launch { results = store.searchCatalog(query, category) }
            }),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.entries.take(10).forEach { (cat, n) ->
                FilterChip(selected = category == cat,
                    onClick = {
                        category = if (category == cat) "" else cat
                        scope.launch {
                            results = store.searchCatalog(query, category)
                        }
                    },
                    label = { Text("${cat.display(simplified)} $n") })
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results.size, key = { results[it].id }) { i ->
                val u = results[i]
                Card(Modifier.fillMaxWidth().clickable { onOpen(u) }) {
                    Column(Modifier.padding(10.dp)) {
                        Text(u.title.display(simplified),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            listOf(u.author, u.dynasty, u.category,
                                "${u.approxChars / 1000}k 字")
                                .filter { it.isNotBlank() }
                                .joinToString(" · ").display(simplified),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun GrepTab(
    store: LibraryStore,
    simplified: Boolean,
    onOpen: (LibraryStore.Unit_, section: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<LibraryStore.GrepHit>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var searched by remember { mutableStateOf(false) }

    fun run() {
        if (query.isBlank() || running) return
        scope.launch {
            running = true; searched = false
            hits = store.grep(query) { done, total ->
                progress = if (total == 0) 1f else done.toFloat() / total
            }
            running = false; searched = true
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text("全库原文检索，如：奔豚 / 四逆汤") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
        )
        Text("稀字倒排索引剪枝候选书 → 逐书流式验证（与服务端同算法）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp))
        if (running) {
            LinearProgressIndicator(progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
        if (searched && hits.isEmpty()) {
            NoticeBar("全库未检得该词")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(hits.size) { i ->
                val h = hits[i]
                Card(Modifier.fillMaxWidth()
                    .clickable { onOpen(h.unit, h.section) }) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            (h.unit.title +
                                (h.section.takeIf { it.isNotBlank() }
                                    ?.let { " · $it" } ?: ""))
                                .display(simplified),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        Text("…${h.excerpt.display(simplified)}…",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
