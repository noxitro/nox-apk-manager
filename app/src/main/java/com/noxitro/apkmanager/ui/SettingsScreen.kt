package com.noxitro.apkmanager.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noxitro.apkmanager.BuildConfig
import com.noxitro.apkmanager.model.Variant

/**
 * 設定。項目は 3 つだけ: 既定の variant / Drive の接続状態 / インストール許可。
 */
@Composable
fun SettingsScreen(
    state: HomeUiState,
    contentPadding: PaddingValues,
    canInstallPackages: Boolean,
    onVariant: (Variant) -> Unit,
    onReload: () -> Unit,
    onPickKey: () -> Unit,
    onCopied: () -> Unit,
    onOpenInstallPermission: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // 広い窓(タブレット / Quest の 2D パネル)で 1 行が長くなりすぎないよう、
    // 本文の幅を頭打ちにして中央に置く。狭い端末では今までどおり全幅。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
    ) {
    Column(
        modifier = Modifier
            .widthIn(max = MAX_TEXT_WIDTH)
            .padding(horizontal = 20.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))

        SectionTitle("既定で入れるビルド")
        Text(
            "両方あるプロジェクトでどちらを「更新」の対象にするか。debug と release は署名が違うと相互に上書きできません。",
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Variant.entries.forEachIndexed { i, v ->
                SegmentedButton(
                    selected = state.preferredVariant == v,
                    onClick = { onVariant(v) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = Variant.entries.size),
                ) { Text(v.fileSuffix) }
            }
        }

        Spacer(Modifier.height(28.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("Google Drive")
        val status = when (val s = state.sync) {
            is SyncState.Ready -> "接続済み。builds/ を ${s.syncedAtLabel} に読みました"
            SyncState.Loading -> "読み込み中"
            is SyncState.Error -> s.message
            SyncState.Idle -> "未接続"
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, color = muted)
        if (state.serviceAccountEmail != null) {
            Spacer(Modifier.height(12.dp))
            // builds/ の共有先。未共有のときに貼り付けられるよう、いつでもコピーできるようにしておく。
            ShareGuide(state.serviceAccountEmail, onCopied)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReload) { Text("もう一度読む") }
            OutlinedButton(onClick = onPickKey) { Text(if (state.serviceAccountEmail == null) "鍵ファイルを選ぶ" else "鍵を入れ替える") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "サービスアカウントで読み取り専用(drive.readonly)で読みます。Google アカウントのログインは要りません。",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "鍵はファイルで選ぶほか、PC から次を実行してアプリを開き直しても入れられます。",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Text(
            "adb push <鍵>.json /sdcard/Android/data/com.noxitro.apkmanager/files/nox-drive-sa.json",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = muted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Drive への書き込みや整理は PC 側の scripts/publish-apk.ps1 が担当します。",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )

        Spacer(Modifier.height(28.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("インストールの許可")
        Text(
            if (canInstallPackages) "このアプリからのインストールは許可されています"
            else "OS の設定で「このアプリからのインストールを許可」を有効にしてください。無いと OS がインストールを拒みます",
            style = MaterialTheme.typography.bodyMedium,
            color = if (canInstallPackages) muted else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenInstallPermission) { Text("OS の設定を開く") }

        Spacer(Modifier.height(28.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
        Row {
            Text("Nox APK Manager ", style = MaterialTheme.typography.bodySmall, color = muted)
            Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

/** 本文が読みやすい 1 行の長さの上限。これ以上広い窓では左右に余白を残す。 */
private val MAX_TEXT_WIDTH = 640.dp

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
}
