package com.noxitro.apkmanager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noxitro.apkmanager.model.AppEntry
import com.noxitro.apkmanager.model.AppStatus
import com.noxitro.apkmanager.ui.theme.TabularNumbers

/**
 * 一覧の 1 行。左にアイコン 48dp、中央に名前・説明・版、右に主ボタン。
 * 行全体のタップで詳細を開く。ボタンは行のタップと別に効く。
 * 作業中は行の直下に進捗バーと 1 行の実況が伸びる。
 */
@Composable
fun AppRow(
    entry: AppEntry,
    icon: ImageBitmap?,
    job: JobState?,
    batchRunning: Boolean,
    onOpenDetail: () -> Unit,
    onInstall: () -> Unit,
    onOpenApp: () -> Unit,
    onDismissJob: () -> Unit,
    onReopenConfirm: () -> Unit,
    onRetry: () -> Unit,
    onUninstallFailed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(label = entry.label, bitmap = icon, size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.description.isNotBlank()) {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                VersionLine(entry)
                // 自分自身は「全て更新」から外してある(HomeViewModel.updateAll)。押す前に分かるように書いておく。
                if (entry.isSelf && entry.status is AppStatus.UpdateAvailable) {
                    Text(
                        text = "このアプリ自身なので、「全て更新」には含めず個別に更新します",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            RowAction(entry, job, batchRunning, onInstall, onOpenApp, onOpenDetail)
        }
        AnimatedVisibility(
            visible = job != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            if (job != null) {
                JobLine(
                    job = job,
                    batchRunning = batchRunning,
                    onDismiss = onDismissJob,
                    onReopenConfirm = onReopenConfirm,
                    onRetry = onRetry,
                    onUninstall = onUninstallFailed,
                )
            }
        }
    }
}

/**
 * 『0.5.0 → 0.6.0 · 15.8 MB · release』の行。入っている版は淡く、取れる版は本文色。
 *
 * 1 つの Text にまとめて組む。要素ごとに Text を並べると、幅が足りないときに
 * 「releas / e」のようにトークンの途中で折り返して読めなくなる(2026-09-06 に実機で発生)。
 */
@Composable
private fun VersionLine(entry: AppEntry) {
    val scheme = MaterialTheme.colorScheme
    val muted = SpanStyle(color = scheme.onSurfaceVariant)
    val strong = SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
    val selected = entry.selected

    val text = buildAnnotatedString {
        when (val s = entry.status) {
            is AppStatus.UpdateAvailable -> {
                withStyle(muted) { append(s.installed.versionName ?: "?") }
                withStyle(muted) { append("  →  ") }
                withStyle(strong) { append(s.latest.versionName) }
            }
            is AppStatus.UpToDate -> {
                withStyle(muted) { append("${s.installed.versionName ?: "?"} · 最新") }
            }
            is AppStatus.LocalNewer -> {
                withStyle(muted) { append("${s.installed.versionName ?: "?"} · 端末の方が新しい(Drive は ${s.latest.versionName})") }
            }
            AppStatus.NotInstalled -> {
                withStyle(muted) { append("${selected?.versionName ?: "—"} · 未導入") }
            }
            AppStatus.Unknown -> {
                withStyle(muted) { append("${selected?.versionName ?: "—"} · 状態不明(meta.json 無し)") }
            }
        }
        if (selected != null && entry.status !is AppStatus.UpToDate) {
            withStyle(muted) { append(" · ${formatSize(selected.sizeBytes)} · ${selected.variant.fileSuffix}") }
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RowAction(
    entry: AppEntry,
    job: JobState?,
    batchRunning: Boolean,
    onInstall: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val busy = job?.isActive == true
    when (entry.status) {
        is AppStatus.UpdateAvailable -> Button(
            onClick = onInstall,
            enabled = !busy && !batchRunning,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) { Text("更新") }
        AppStatus.NotInstalled, AppStatus.Unknown -> FilledTonalButton(
            onClick = onInstall,
            enabled = !busy && !batchRunning && entry.selected != null,
        ) { Text("導入") }
        is AppStatus.UpToDate -> OutlinedButton(onClick = onOpenApp, enabled = !busy) { Text("開く") }
        is AppStatus.LocalNewer -> IconButton(onClick = onOpenDetail) {
            Icon(Icons.Outlined.Info, contentDescription = "${entry.label} の詳細")
        }
    }
}

/**
 * 行の下に伸びる進捗と実況。完了・失敗はタップで畳める。
 * 失敗には次の一手(アンインストール / 再試行)をボタンで添える。理由を読んだその場で押せるように。
 */
@Composable
private fun JobLine(
    job: JobState,
    batchRunning: Boolean,
    onDismiss: () -> Unit,
    onReopenConfirm: () -> Unit,
    onRetry: () -> Unit,
    onUninstall: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(top = 10.dp, start = 64.dp)) {
        if (job.isActive) {
            if (job.progress >= 0f && job.stage == JobState.Stage.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (!job.isActive) it.clickable(onClick = onDismiss) else it }
                .semantics { contentDescription = job.message },
        ) {
            when (job.stage) {
                JobState.Stage.DONE -> Icon(Icons.Default.Check, null, tint = scheme.tertiary, modifier = Modifier.size(16.dp))
                JobState.Stage.FAILED -> Icon(Icons.Default.ErrorOutline, null, tint = scheme.error, modifier = Modifier.size(16.dp))
                else -> Box(Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = job.message,
                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                color = when (job.stage) {
                    JobState.Stage.FAILED -> scheme.error
                    JobState.Stage.DONE -> scheme.tertiary
                    else -> scheme.onSurfaceVariant
                },
            )
        }
        // 確認画面が出ないまま待ちになることがある(バックグラウンドからの起動が OS に弾かれる)。
        // その逃げ道。
        if (job.canReopenConfirm) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onReopenConfirm,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) { Text("確認画面を開く") }
        }
        if (job.canUninstall || job.canRetry) {
            Spacer(Modifier.height(4.dp))
            // 一括更新の最中は OS のダイアログが取り違えられるので押させない。
            if (job.canUninstall) {
                TextButton(
                    onClick = onUninstall,
                    enabled = !batchRunning,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text("アンインストール") }
            } else {
                TextButton(
                    onClick = onRetry,
                    enabled = !batchRunning,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text("再試行") }
            }
        }
    }
}

/** インストール済みアイコン / Drive のアイコン / 頭文字のプレースホルダ。 */
@Composable
fun AppIcon(label: String, bitmap: ImageBitmap?, size: androidx.compose.ui.unit.Dp) {
    val shape = RoundedCornerShape(size * 0.25f)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        val scheme = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(scheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSecondaryContainer,
            )
        }
    }
}
