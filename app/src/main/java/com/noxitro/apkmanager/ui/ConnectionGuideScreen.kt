package com.noxitro.apkmanager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noxitro.apkmanager.ui.SetupStatus.Check

/**
 * 接続方法。Drive につなぐ手順を、端末の中だけで読めるようにまとめた画面。
 *
 * 先頭に「今の状態」(鍵 / 共有 / インストール許可)を出し、未のものから順に手順を読めるようにする。
 * docs/SETUP.md と同じ内容だが、端末からリポジトリの文書は読めないので、ここにも持つ。
 * 設定タブ・ホームの「接続方法」から開く。
 */
@Composable
fun ConnectionGuideScreen(
    state: HomeUiState,
    contentPadding: PaddingValues,
    canInstallPackages: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onPickKey: () -> Unit,
    onCopied: () -> Unit,
    onOpenInstallPermission: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val status = SetupStatus.of(state.serviceAccountEmail, state.sync, canInstallPackages)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(modifier = Modifier.widthIn(max = 640.dp).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                Text("接続方法", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                "このアプリは Google Drive の builds/ フォルダを読み取り専用で読みます。" +
                    "Google アカウントのログインは要りません。代わりに、次の 3 つを用意します。",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )

            Spacer(Modifier.height(16.dp))
            StatusBoard(state, status, onReload)

            GuideSection("スマホだけで設定する") {
                Text(
                    "PC は要りません。スマホのブラウザで鍵を作り、ファイルで選びます(5〜10 分)。" +
                        "Google Cloud の画面が崩れるときは、Chrome のメニューで「PC 版サイト」をオンにします。",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                Spacer(Modifier.height(8.dp))
                PhoneSetupSteps()
                Spacer(Modifier.height(8.dp))
                PickKeyButton(onPickKey)
            }

            GuideSection("builds/ を共有する") {
                val email = state.serviceAccountEmail
                if (email != null) {
                    ShareGuide(email, onCopied)
                } else {
                    Text(
                        "鍵を入れると、ここに共有先のアドレスとコピーのボタンが出ます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "共有するのはマイドライブ直下の builds フォルダ 1 つだけで、権限は「閲覧者」で足ります。" +
                        "中のプロジェクトフォルダは自動で見えます。アプリは名前で探すので、フォルダ名は builds のままにします。",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }

            GuideSection("インストールを許可する") {
                Text(
                    "OS の設定で「この提供元のアプリを許可」をオンにします。無いと、更新を押しても OS がインストールを拒みます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenInstallPermission) { Text("OS の設定を開く") }
            }

            GuideSection("PC から設定する(Quest など)") {
                Text(
                    "文字入力が辛い端末は、PC から入れるのが楽です。端末を USB でつなぎ、このリポジトリで次の 2 つを実行します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                Spacer(Modifier.height(4.dp))
                CopyableCommand("pwsh scripts\\setup-gcp.ps1", onCopied)
                Text(
                    "Google 側をまとめて行います(プロジェクト・Drive API・サービスアカウント・鍵・builds/ の共有)。" +
                        "gcloud が要ります(winget install Google.CloudSDK)。",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                Spacer(Modifier.height(8.dp))
                CopyableCommand("pwsh scripts\\setup-device.ps1", onCopied)
                Text(
                    "このアプリを入れ、鍵を送り、取り込まれたかを確かめ、インストール許可の画面を開きます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "鍵だけを手で送るなら、先に一度このアプリを開いてから:",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                CopyableCommand("adb push <鍵>.json /sdcard/Android/data/com.noxitro.apkmanager/files/nox-drive-sa.json", onCopied)
                Text(
                    "送ったら「もう一度読む」。アプリが鍵を取り込み、送ったファイルは消します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }

            GuideSection("うまくいかないとき") {
                Trouble("「Drive の鍵がまだ入っていません」のまま", "鍵を選んでいない、または送った場所が違います。画面の「探した場所」「あったもの」を見てください。")
                Trouble("「OAuth クライアントの JSON です」", "旧方式の JSON です。サービスアカウントの「キー」タブで作った JSON を選び直してください。")
                Trouble("「Drive API が有効になっていません」", "鍵を作ったプロジェクトで Google Drive API を有効にします(スマホの手順 2)。")
                Trouble("「builds/ がまだ共有されていません」", "builds をサービスアカウントのアドレスに共有していないか、フォルダ名が builds ではありません。")
                Trouble("「トークンを取得できません」", "鍵かサービスアカウントを削除した、または端末の時計がずれています。鍵を作り直すか、時計を合わせます。")
                Trouble("「署名が違うので上書きできません」", "端末に入っているのが別の鍵で署名された版です(debug と release の取り違え等)。アンインストールしてから入れ直します。")
                Trouble("「端末に入っている版の方が新しい」", "Drive より新しい版が入っています。戻すならアンインストールしてから入れ直します。")
            }

            GuideSection("配布フォルダの決まり") {
                Text(
                    "builds/ の中のフォルダ 1 つが、一覧の 1 アプリになります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "builds/<project>/\n" +
                            "  <project>-<版>-<debug|release>.apk\n" +
                            "  meta.json   … 任意。package 名・説明\n" +
                            "  icon.png    … 任意。未導入の行のアイコン",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "置くのは PC の scripts/publish-apk.ps1 か、GitHub Actions の publish-apk ワークフローです。" +
                        "このアプリは Drive に書き込みません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 3 つの準備が済んでいるかの板。未のものは、どの節を読めばよいかを添える。 */
@Composable
private fun StatusBoard(state: HomeUiState, status: SetupStatus, onReload: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (status.allDone) "準備はすべて済んでいます" else "今の状態",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            StatusRow(
                label = "1. 鍵(サービスアカウントの JSON)",
                check = status.key,
                detail = when (status.key) {
                    Check.DONE -> state.serviceAccountEmail ?: "入っています"
                    Check.TODO -> "まだ入っていません →「スマホだけで設定する」"
                    Check.UNKNOWN -> "確かめています…"
                },
            )
            StatusRow(
                label = "2. builds/ の共有",
                check = status.share,
                detail = when (status.share) {
                    Check.DONE -> "読めています"
                    Check.TODO -> "共有されていません →「builds/ を共有する」"
                    Check.UNKNOWN -> if (status.key == Check.TODO) "鍵を入れると確かめられます" else "まだ確かめられていません(下の「もう一度読む」)"
                },
            )
            StatusRow(
                label = "3. インストールの許可",
                check = status.install,
                detail = if (status.install == Check.DONE) "許可されています" else "まだです →「インストールを許可する」",
            )
            (state.sync as? SyncState.Error)?.let {
                Spacer(Modifier.height(4.dp))
                Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onReload, enabled = state.sync !is SyncState.Loading) { Text("もう一度読む") }
        }
    }
}

@Composable
private fun StatusRow(label: String, check: Check, detail: String) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val (icon, tint, description) = when (check) {
            Check.DONE -> Triple(Icons.Filled.CheckCircle, scheme.tertiary, "済み")
            Check.TODO -> Triple(Icons.Outlined.RadioButtonUnchecked, scheme.error, "未")
            Check.UNKNOWN -> Triple(Icons.AutoMirrored.Outlined.HelpOutline, scheme.onSurfaceVariant, "不明")
        }
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GuideSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Column(content = content)
}

@Composable
private fun Trouble(symptom: String, fix: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(symptom, style = MaterialTheme.typography.bodyMedium)
        Text(fix, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
