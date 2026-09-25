package com.noxitro.apkmanager

import android.app.Application
import com.noxitro.apkmanager.auth.ServiceAccountAuth
import com.noxitro.apkmanager.data.CatalogRepository
import com.noxitro.apkmanager.data.Prefs
import com.noxitro.apkmanager.drive.DriveApi
import com.noxitro.apkmanager.drive.DriveSource
import com.noxitro.apkmanager.drive.LocalDriveSource
import com.noxitro.apkmanager.install.ApkInstaller
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 依存の組み立て。画面が 2 つしか無いので DI フレームワークは入れない。
 */
class ApkManagerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val prefs = Prefs(app)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Drive の認証。ユーザーのアカウントではなくサービスアカウントで読む
     * (GMS 非搭載の端末でも動き、同意画面の 7 日失効に当たらない。docs/SETUP.md)。
     */
    val auth = ServiceAccountAuth(http, keyJsonProvider = { prefs.serviceAccountKeyOnce() })

    /**
     * `adb push` で置かれた鍵を取り込む口。アプリ専用の外部ストレージの [KEY_FILE_NAME] を読み、
     * DataStore に移してから**元のファイルを消す**(秘密鍵を外部ストレージに残さない)。
     * 取り込むものが無ければ何もしない。
     */
    private val pushedKeyFile: File = File(app.getExternalFilesDir(null), KEY_FILE_NAME)

    suspend fun importPushedKeyIfAny(): Boolean {
        if (!pushedKeyFile.isFile) return false
        val text = runCatching { pushedKeyFile.readText() }.getOrNull() ?: return false
        if (!text.contains("private_key")) return false
        prefs.setServiceAccountKey(text)
        // DataStore への書き込みが済んでから消す。ここで失敗しても次回もう一度取り込むだけ。
        pushedKeyFile.delete()
        auth.invalidate()
        return true
    }

    /**
     * debug ビルドで、アプリ専用の外部ストレージに builds/ が置いてあればそれを読む
     * (`adb push` で実物の配布フォルダをそのまま置ける。認証不要で一覧とインストールを検証する用)。
     * release ビルドは常に Drive。
     */
    private val localBuilds: File? =
        if (BuildConfig.DEBUG) File(app.getExternalFilesDir(null), "builds").takeIf { it.isDirectory } else null

    val isLocalSource: Boolean get() = localBuilds != null

    val drive: DriveSource = localBuilds?.let { LocalDriveSource(it) }
        ?: DriveApi(http) { force -> auth.accessToken(force) }

    val repository = CatalogRepository(app, drive, prefs)
    val installer = ApkInstaller(app)

    companion object {
        /** `adb push <鍵> /sdcard/Android/data/com.noxitro.apkmanager/files/<この名前>` */
        const val KEY_FILE_NAME = "nox-drive-sa.json"
    }
}
