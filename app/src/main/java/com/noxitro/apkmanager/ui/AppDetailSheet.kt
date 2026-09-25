package com.noxitro.apkmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noxitro.apkmanager.model.ApkBuild
import com.noxitro.apkmanager.model.AppEntry
import com.noxitro.apkmanager.model.AppStatus
import com.noxitro.apkmanager.ui.theme.TabularNumbers

/**
 * 行をタップしたときのボトムシート。
 * 端末の現状、Drive にある全ビルド(版ごとに debug / release)、開く / アンインストール。
 * 任意の版を入れられるので「戻す」にも使う(端末側が新しい場合は OS が拒むので理由を先に出す)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailSheet(
    entry: AppEntry,
    icon: ImageBitmap?,
    job: JobState?,
    onDismiss: () -> Unit,
    onInstall: (ApkBuild) -> Unit,
    onOpenApp: () -> Unit,
    onUninstall: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val busy = job?.isActive == true

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(label = entry.label, bitmap = icon, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        entry.packageName ?: "package 名は未判明(ダウンロードすると分かります)",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
            if (entry.description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(entry.description, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(20.dp))
            Text("この端末", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            val installedLine = when (val s = entry.status) {
                is AppStatus.UpdateAvailable -> "${s.installed.versionName}(code ${s.installed.versionCode})が入っています。Drive の ${s.latest.versionName} が新しいです"
                is AppStatus.UpToDate -> "${s.installed.versionName}(code ${s.installed.versionCode})。Drive の最新と同じです"
                is AppStatus.LocalNewer -> "${s.installed.versionName}(code ${s.installed.versionCode})。Drive の ${s.latest.versionName} より新しいので、戻すにはアンインストールが必要です"
                AppStatus.NotInstalled -> "入っていません"
                AppStatus.Unknown -> "package 名が分からないので判定できません。一度導入すると次回から比較できます"
            }
            Text(installedLine, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), color = muted)
            if (entry.isSelf) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "これはこのアプリ自身です。更新すると OS がプロセスを入れ替えるため一度終了します。" +
                        "開き直せば新しい版になっています。「全て更新」の対象からは外してあります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (job != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    job.message,
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                    color = when (job.stage) {
                        JobState.Stage.FAILED -> MaterialTheme.colorScheme.error
                        JobState.Stage.DONE -> MaterialTheme.colorScheme.tertiary
                        else -> muted
                    },
                )
            }
            if (entry.isInstalled) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onOpenApp, enabled = !busy) { Text("開く") }
                    OutlinedButton(onClick = onUninstall, enabled = !busy) { Text("アンインストール") }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Drive にあるビルド", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (!entry.hasMeta) {
                Text(
                    "meta.json が無いので版はファイル名から読んでいます",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            Spacer(Modifier.height(4.dp))
            if (entry.builds.isEmpty()) {
                Text("規約(名前-版-debug|release.apk)に合う APK がありません", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            entry.builds.forEach { build ->
                val isSelected = build == entry.selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                build.versionName,
                                style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(build.variant.fileSuffix, style = MaterialTheme.typography.labelMedium, color = muted)
                            if (isSelected) Text("既定", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            listOfNotNull(
                                formatSize(build.sizeBytes),
                                build.versionCode?.let { "code $it" },
                                build.modifiedTime?.take(10),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                            color = muted,
                        )
                    }
                    if (isSelected && entry.status is AppStatus.UpdateAvailable) {
                        Button(onClick = { onInstall(build) }, enabled = !busy) { Text("更新") }
                    } else {
                        TextButton(onClick = { onInstall(build) }, enabled = !busy) { Text("入れる") }
                    }
                }
            }
        }
    }
}
