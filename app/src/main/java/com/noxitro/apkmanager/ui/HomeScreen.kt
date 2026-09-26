package com.noxitro.apkmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noxitro.apkmanager.model.AppEntry
import com.noxitro.apkmanager.ui.theme.TabularNumbers

/**
 * ホーム。上から: ヒーロー帯 / 絞り込みチップ / 「更新あり N 件」の板 / 導入済み・未導入の行。
 * 板の中の行と下の行は同じ AppRow。違いは板の面(surfaceContainer)だけ。
 *
 * 幅が [MIN_ROW_WIDTH] の 2 倍以上ある窓(タブレット、Meta Quest の 2D パネル)では、
 * 行が横に間延びしないように複数列に流す。板の中も同じ列数で並べるが、
 * 板は「影も枠線も持たない 1 枚の面」なので([DESIGN.md] の The Flat Board Rule)、
 * 列ごとに面を割らず、1 枚の Surface の中で行を並べる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onReload: () -> Unit,
    onPickKey: () -> Unit,
    onCopied: () -> Unit,
    onOpenGuide: () -> Unit,
    onFilter: (Filter) -> Unit,
    onUpdateAll: () -> Unit,
    onCancelBatch: () -> Unit,
    onOpenDetail: (AppEntry) -> Unit,
    onInstall: (AppEntry) -> Unit,
    onOpenApp: (AppEntry) -> Unit,
    onDismissJob: (String) -> Unit,
    onReopenConfirm: (String) -> Unit,
) {
    val listState = rememberLazyGridState()
    val loading = state.sync is SyncState.Loading

    PullToRefreshBox(
        isRefreshing = loading && state.entries.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            // 行は「アイコン + 名前と説明 + ボタン」なので、これ以上狭いと説明が読めなくなる。
            columns = GridCells.Adaptive(MIN_ROW_WIDTH),
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            fullWidth(key = "hero") { Hero(state, onRefresh, onOpenGuide) }

            fullWidth(key = "chips") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Filter.entries.forEach { f ->
                        val count = when (f) {
                            Filter.ALL -> state.entries.size
                            Filter.UPDATES -> state.updates.size
                            Filter.NOT_INSTALLED -> state.entries.count { !it.isInstalled }
                            Filter.INSTALLED -> state.entries.count { it.isInstalled }
                        }
                        FilterChip(
                            selected = state.filter == f,
                            onClick = { onFilter(f) },
                            label = { Text(if (state.entries.isEmpty()) f.label else "${f.label} $count") },
                        )
                    }
                }
            }

            when (val sync = state.sync) {
                is SyncState.Error -> if (state.entries.isEmpty()) {
                    fullWidth(key = "error") {
                        ErrorBlock(
                            sync,
                            state.serviceAccountEmail,
                            onRetry = onRefresh,
                            onReload = onReload,
                            onPickKey = onPickKey,
                            onCopied = onCopied,
                            onOpenGuide = onOpenGuide,
                        )
                    }
                    return@LazyVerticalGrid
                }
                SyncState.Loading, SyncState.Idle -> if (state.entries.isEmpty()) {
                    fullWidth(key = "loading") { LoadingBlock() }
                    return@LazyVerticalGrid
                }
                is SyncState.Ready -> Unit
            }

            if (state.entries.isNotEmpty()) {
                if (state.filter == Filter.ALL || state.filter == Filter.UPDATES) {
                    updateBoard(state, onUpdateAll, onCancelBatch, onOpenDetail, onInstall, onOpenApp, onDismissJob, onReopenConfirm)
                }
                val rest = if (state.filter == Filter.ALL) state.entries.filter { !it.isUpdateAvailable } else if (state.filter == Filter.UPDATES) emptyList() else state.filtered
                if (rest.isNotEmpty()) {
                    val installed = rest.filter { it.isInstalled }
                    val others = rest.filter { !it.isInstalled }
                    if (installed.isNotEmpty()) {
                        sectionHeader("section-installed", "導入済み", installed.size)
                        rows(installed, state, onOpenDetail, onInstall, onOpenApp, onDismissJob, onReopenConfirm)
                    }
                    if (others.isNotEmpty()) {
                        sectionHeader("section-others", "未導入", others.size)
                        rows(others, state, onOpenDetail, onInstall, onOpenApp, onDismissJob, onReopenConfirm)
                    }
                } else if (state.filter != Filter.ALL && state.filtered.isEmpty()) {
                    fullWidth(key = "empty-filter") { EmptyFilter(state.filter) }
                }
            }

            if (state.sync is SyncState.Error && state.entries.isNotEmpty()) {
                fullWidth(key = "error-footer") {
                    Text(
                        text = (state.sync as SyncState.Error).message + "(前回の一覧を表示中)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(state: HomeUiState, onRefresh: () -> Unit, onOpenGuide: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Nox APK Manager", style = MaterialTheme.typography.headlineSmall)
                val sub = when (val s = state.sync) {
                    is SyncState.Ready -> "Drive の builds/ · ${s.syncedAtLabel} に同期"
                    SyncState.Loading -> "Drive を読んでいます…"
                    is SyncState.Error -> when {
                        s.needsKey -> "鍵が未設定"
                        s.needsShare -> "builds/ が未共有"
                        else -> "同期できていません"
                    }
                    SyncState.Idle -> ""
                }
                Text(sub, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), color = scheme.onSurfaceVariant)
            }
            IconButton(onClick = onOpenGuide) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "接続方法")
            }
            IconButton(onClick = onRefresh, enabled = state.sync !is SyncState.Loading) {
                Icon(Icons.Default.Refresh, contentDescription = "Drive を読み直す")
            }
        }
    }
}

/** 「更新あり N 件 ─ 全て更新」の板。更新対象の行を中に抱える。 */
private fun LazyGridScope.updateBoard(
    state: HomeUiState,
    onUpdateAll: () -> Unit,
    onCancelBatch: () -> Unit,
    onOpenDetail: (AppEntry) -> Unit,
    onInstall: (AppEntry) -> Unit,
    onOpenApp: (AppEntry) -> Unit,
    onDismissJob: (String) -> Unit,
    onReopenConfirm: (String) -> Unit,
) {
    val updates = state.updates
    // 板は 1 枚の面。列が増えても面を割らないよう、板ごと 1 つのセルに入れて
    // 中で行を並べる(更新のある行はたかだかプロジェクト数なので、遅延にしなくてよい)。
    fullWidth(key = "board") {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (updates.isEmpty()) "すべて最新です" else "更新あり",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (updates.isNotEmpty()) {
                            val total = updates.sumOf { it.selected?.sizeBytes ?: 0L }
                            Text(
                                "${updates.size} 件 · 合計 ${formatSize(total)}",
                                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "${state.entries.count { it.isInstalled }} 本が Drive の最新版と一致",
                                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (updates.isNotEmpty()) {
                        if (state.batchRunning) {
                            OutlinedButton(onClick = onCancelBatch) { Text("中止") }
                        } else {
                            Button(onClick = onUpdateAll) { Text("全て更新") }
                        }
                    }
                }
                if (updates.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    BoxWithConstraints {
                        val columns = (maxWidth / MIN_ROW_WIDTH).toInt().coerceAtLeast(1)
                        Column {
                            updates.chunked(columns).forEach { row ->
                                Row {
                                    row.forEach { entry ->
                                        Box(modifier = Modifier.weight(1f)) {
                                            AppRow(
                                                entry = entry,
                                                icon = state.icons[entry.project],
                                                job = state.jobs[entry.project],
                                                batchRunning = state.batchRunning,
                                                onOpenDetail = { onOpenDetail(entry) },
                                                onInstall = { onInstall(entry) },
                                                onOpenApp = { onOpenApp(entry) },
                                                onDismissJob = { onDismissJob(entry.project) },
                                                onReopenConfirm = { onReopenConfirm(entry.project) },
                                            )
                                        }
                                    }
                                    // 端数の列は空けておく。行の幅を揃えるため。
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.sectionHeader(key: String, title: String, count: Int) {
    fullWidth(key = key) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyGridScope.rows(
    entries: List<AppEntry>,
    state: HomeUiState,
    onOpenDetail: (AppEntry) -> Unit,
    onInstall: (AppEntry) -> Unit,
    onOpenApp: (AppEntry) -> Unit,
    onDismissJob: (String) -> Unit,
    onReopenConfirm: (String) -> Unit,
) {
    items(entries, key = { "r-" + it.project }) { entry ->
        AppRow(
            entry = entry,
            icon = state.icons[entry.project],
            job = state.jobs[entry.project],
            batchRunning = state.batchRunning,
            onOpenDetail = { onOpenDetail(entry) },
            onInstall = { onInstall(entry) },
            onOpenApp = { onOpenApp(entry) },
            onDismissJob = { onDismissJob(entry.project) },
            onReopenConfirm = { onReopenConfirm(entry.project) },
        )
    }
}

@Composable
private fun LoadingBlock() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Drive の builds/ を読んでいます", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorBlock(
    error: SyncState.Error,
    serviceAccountEmail: String?,
    onRetry: () -> Unit,
    onReload: () -> Unit,
    onPickKey: () -> Unit,
    onCopied: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = if (error.needsKey) 24.dp else 64.dp),
    ) {
        Icon(
            Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                error.needsKey -> "Drive の鍵がまだ入っていません"
                error.needsShare -> "builds/ がまだ共有されていません"
                else -> "読み込めませんでした"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            error.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        when {
            // スマホ単体で完結する手順(ブラウザで鍵を作る → ファイルを選ぶ)。PC からの adb push も末尾に残す。
            error.needsKey -> {
                Spacer(Modifier.height(20.dp))
                Box(modifier = Modifier.widthIn(max = 480.dp)) { KeySetupGuide(onPickKey, onOpenGuide) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onReload) { Text("もう一度読む") }
            }
            error.needsShare && serviceAccountEmail != null -> {
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.widthIn(max = 480.dp)) { ShareGuide(serviceAccountEmail, onCopied) }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("もう一度読む") }
                TextButton(onClick = onOpenGuide) { Text("接続方法を見る") }
            }
            else -> {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRetry) { Text("もう一度読む") }
                TextButton(onClick = onOpenGuide) { Text("接続方法を見る") }
            }
        }
    }
}

@Composable
private fun EmptyFilter(filter: Filter) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    ) {
        Icon(Icons.Outlined.Inbox, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            when (filter) {
                Filter.UPDATES -> "更新のあるアプリはありません"
                Filter.NOT_INSTALLED -> "Drive にある全部が入っています"
                Filter.INSTALLED -> "まだ 1 本も入っていません"
                Filter.ALL -> "Drive の builds/ にフォルダがありません"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 列数によらず 1 行を占める項目(ヒーロー帯・チップ列・見出し・板・状態表示)。 */
private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/**
 * 1 列に必要な最小の幅。アイコン 48dp + 名前と 1 行の説明 + ボタンが収まる下限。
 * これを下回ると説明が読めなくなるので、幅がこの 2 倍に届くまで列は増やさない。
 */
private val MIN_ROW_WIDTH = 380.dp
