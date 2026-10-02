package com.noxitro.apkmanager.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Google Drive REST v3 の、このアプリが使う分だけ。
 * Google の Java クライアントは重いので OkHttp で直接叩く。
 *
 * すべて suspend で、IO ディスパッチャに寄せる。失敗は [DriveException] で投げる。
 * 401/403 は 1 回だけトークンを取り直して自分で再試行する([tokenProvider] に true を渡す)。
 */
class DriveApi(
    private val client: OkHttpClient,
    /** [forceRefresh] が true なら、キャッシュを捨てて取り直す。 */
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String,
) : DriveSource {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class DriveFile(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long? = null,
        val modifiedTime: String? = null,
        val md5Checksum: String? = null,
    )

    @Serializable
    private data class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val BASE = "https://www.googleapis.com/drive/v3/files"
        private const val FIELDS = "nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum)"
    }

    /**
     * `builds` フォルダ。無ければ null。
     *
     * サービスアカウントは自分のマイドライブを持たないので、`'root' in parents` では見つからない。
     * 共有されたものは「共有アイテム」に入るので `sharedWithMe` で探す。
     * ユーザー本人のアカウントで読む場合に備えて、見つからなければマイドライブ直下も見る。
     */
    override suspend fun findBuildsFolder(): DriveFile? {
        val shared = query("sharedWithMe and name = 'builds' and mimeType = '$FOLDER_MIME' and trashed = false")
        if (shared.isNotEmpty()) return shared.first()
        return query("name = 'builds' and mimeType = '$FOLDER_MIME' and 'root' in parents and trashed = false")
            .firstOrNull()
    }

    override suspend fun listChildren(folderId: String): List<DriveFile> =
        query("'$folderId' in parents and trashed = false")

    private suspend fun query(q: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = BASE.toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", FIELDS)
                .addQueryParameter("pageSize", "200")
                .addQueryParameter("orderBy", "name")
                .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
                .build()
            val body = execute(Request.Builder().url(url)).use { it.body?.string().orEmpty() }
            val page = json.decodeFromString<FileList>(body)
            out += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        out
    }

    /** 小さいテキスト(meta.json)を丸ごと読む。 */
    override suspend fun downloadText(fileId: String): String = withContext(Dispatchers.IO) {
        execute(mediaRequest(fileId)).use { it.body?.string().orEmpty() }
    }

    /**
     * APK をファイルに落とす。進捗は 0..1 で通知する(サイズ不明なら -1)。
     * 途中で失敗したら部分ファイルは消す(半端な APK を PackageInstaller に渡さないため)。
     */
    override suspend fun downloadToFile(
        fileId: String,
        dest: File,
        expectedSize: Long?,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            execute(mediaRequest(fileId)).use { resp ->
                val body = resp.body ?: throw DriveException(resp.code, "empty body")
                val total = expectedSize ?: body.contentLength().takeIf { it > 0 }
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReported = -1f
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            val p = if (total != null && total > 0) (done.toFloat() / total) else -1f
                            // 描画負荷を抑えるため 1% 刻みでだけ通知する
                            if (p < 0 || p - lastReported >= 0.01f) {
                                lastReported = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("rename failed: $tmp -> $dest")
            onProgress(1f)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun mediaRequest(fileId: String) =
        Request.Builder().url("$BASE/$fileId?alt=media")

    /**
     * 認証つきで叩く。401/403 のときだけトークンを取り直して 1 回やり直す
     * (アクセストークンは 1 時間で切れるので、長く開きっぱなしにしていると普通に起きる)。
     */
    private suspend fun execute(builder: Request.Builder): okhttp3.Response {
        val first = attempt(builder, forceRefresh = false)
        if (first.isSuccessful) return first
        val msg = first.body?.string().orEmpty().take(500)
        first.close()
        if (first.code != 401 && first.code != 403) throw DriveException(first.code, msg)

        val retry = attempt(builder, forceRefresh = true)
        if (retry.isSuccessful) return retry
        val retryMsg = retry.body?.string().orEmpty().take(500)
        retry.close()
        throw DriveException(retry.code, retryMsg)
    }

    private suspend fun attempt(builder: Request.Builder, forceRefresh: Boolean): okhttp3.Response {
        val token = tokenProvider(forceRefresh)
        return client.newCall(builder.header("Authorization", "Bearer $token").build()).execute()
    }
}

class DriveException(val code: Int, message: String) : IOException("Drive HTTP $code: $message") {
    val isAuthError: Boolean get() = code == 401 || code == 403

    /**
     * プロジェクトで Drive API が有効になっていない(403 accessNotConfigured / SERVICE_DISABLED)。
     * トークンは取れるのに読み取りだけ 403 になるので、「共有していない」と取り違えやすい。
     * 共有していないときはサービスアカウントから builds が見えないだけで、403 にはならない。
     */
    val isApiDisabled: Boolean
        get() = code == 403 && API_DISABLED_MARKERS.any { message.orEmpty().contains(it) }

    private companion object {
        val API_DISABLED_MARKERS = listOf("accessNotConfigured", "SERVICE_DISABLED", "has not been used in project")
    }
}
