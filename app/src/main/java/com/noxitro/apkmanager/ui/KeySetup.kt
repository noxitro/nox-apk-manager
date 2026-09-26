package com.noxitro.apkmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 鍵が無いときの手順。**スマホ単体で完結する**ように組んである。
 *
 * - Google Cloud の作業はスマホのブラウザで行う。各手順から該当ページを直接開く。
 * - 鍵(JSON)はダウンロードに保存されるので、[onPickKey] のファイル選択で取り込む。
 * - PC があるなら adb push でもよい(Quest のような入力の辛い端末向け)。末尾に小さく書く。
 */
@Composable
fun KeySetupGuide(onPickKey: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onPickKey, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("鍵ファイル(JSON)を選ぶ")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "ダウンロードした鍵を選ぶと取り込みます。取り込んだ後、端末の元ファイルは消します。",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )

        Spacer(Modifier.height(20.dp))
        Text("鍵をまだ作っていないとき(スマホのブラウザで 5 分)", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        GuideStep(
            number = 1,
            title = "プロジェクトを作る",
            body = "名前は何でもよい(例: nox-apk-manager)。作った後、画面上部で選ばれていることを確かめる。",
            linkLabel = "プロジェクトの作成を開く",
            url = "https://console.cloud.google.com/projectcreate",
        )
        GuideStep(
            number = 2,
            title = "Google Drive API を有効にする",
            body = "「有効にする」を押す。",
            linkLabel = "Drive API を開く",
            url = "https://console.cloud.google.com/apis/library/drive.googleapis.com",
        )
        GuideStep(
            number = 3,
            title = "サービスアカウントを作る",
            body = "名前を入れて「作成して続行」。ロールなどは空のまま「完了」。",
            linkLabel = "サービスアカウントの作成を開く",
            url = "https://console.cloud.google.com/iam-admin/serviceaccounts/create",
        )
        GuideStep(
            number = 4,
            title = "鍵(JSON)を作る",
            body = "一覧から作ったアカウントを開き「キー」→「鍵を追加」→「新しい鍵を作成」→ JSON。" +
                "ダウンロードに保存されるので、上の「鍵ファイルを選ぶ」で選ぶ。",
            linkLabel = "サービスアカウントの一覧を開く",
            url = "https://console.cloud.google.com/iam-admin/serviceaccounts",
        )
        GuideStep(
            number = 5,
            title = "Drive の builds/ を共有する",
            body = "鍵を取り込むと、共有先のアドレスとコピーのボタンが出ます。",
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "PC からなら: adb push <鍵>.json /sdcard/Android/data/com.noxitro.apkmanager/files/nox-drive-sa.json",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = muted,
        )
    }
}

@Composable
private fun GuideStep(number: Int, title: String, body: String, linkLabel: String? = null, url: String? = null) {
    val uriHandler = LocalUriHandler.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(24.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (linkLabel != null && url != null) {
                TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                    Text(linkLabel)
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                }
            }
        }
    }
}

/**
 * 鍵は入ったが builds/ がサービスアカウントに共有されていないとき。
 * アドレスをコピーして Drive(アプリがあればアプリ)を開き、共有に貼り付けてもらう。
 */
@Composable
fun ShareGuide(email: String, onCopied: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Drive の builds フォルダを、次のアドレスに「閲覧者」で共有してください。",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                email,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(email))
                onCopied()
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("コピー")
            }
            OutlinedButton(onClick = { runCatching { uriHandler.openUri("https://drive.google.com/drive/my-drive") } }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("Drive を開く")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "builds の「︙」→「共有」→ アドレスを貼り付け → 閲覧者 → 送信。共有したら「もう一度読む」。",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}
