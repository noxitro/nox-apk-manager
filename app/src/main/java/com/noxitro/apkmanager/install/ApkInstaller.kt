package com.noxitro.apkmanager.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * PackageInstaller のセッション API で APK を入れる。
 *
 * `ACTION_VIEW` + FileProvider でも入るが、結果が取れず「全て更新」が組めない。
 * セッション方式なら [InstallResultReceiver] に成功 / 失敗 / 確認待ち が届く。
 *
 * 結果の待ち受けは **commit の前に** 登録する([InstallEvents.register])。
 * commit 後に登録すると、OS が確認なしで即座に終わらせた場合
 * (Android 12 以降、このアプリが該当パッケージの installer of record のとき)に
 * 成功通知が登録より先に届き、取りこぼして永久に待つ。2026-09-06 に実機で発生。
 */
class ApkInstaller(private val context: Context) {

    sealed interface Result {
        data class Success(val packageName: String?) : Result
        data class Failure(val status: Int, val message: String, val packageName: String?) : Result {
            /** 端末側と署名が違う(debug と release の取り違え、または鍵の変更)。 */
            val isSignatureMismatch: Boolean
                get() = message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
                    message.contains("signatures do not match", ignoreCase = true)

            /** 端末側の方が新しい版。 */
            val isDowngrade: Boolean
                get() = message.contains("INSTALL_FAILED_VERSION_DOWNGRADE")
        }
        /**
         * OS が中止を返した(`STATUS_FAILURE_ABORTED`)。確認画面で取り消したときのほか、
         * セッションが破棄されたときなどにも来る。**理由は [message] に入っている**ので捨てない
         * (0.7.0 までは捨てていて、画面に「キャンセルされました」としか出せなかった)。
         *
         * @param legacyStatus 古い形の失敗コード(`INSTALL_FAILED_*` の数値)。来ないこともある。
         */
        data class Aborted(val message: String, val legacyStatus: Int? = null) : Result {
            /** 画面に出す文。 */
            val description: String get() = AbortReason.describe(message, legacyStatus)
        }
    }

    /**
     * APK を入れて結果を返す。
     * [onSession] にはセッション ID を渡す(呼び出し側が確認ダイアログを開き直すのに使う)。
     */
    suspend fun install(
        apk: File,
        packageNameHint: String?,
        onSession: (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            packageNameHint?.let { setAppPackageName(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 確認なしで済むならその方が速い。済まなければ OS が確認画面を要求してくる。
                //
                // 無確認で通る条件は 2 つ。どちらか欠けると、この指定は**黙って無視される**。
                //   1. マニフェストに UPDATE_PACKAGES_WITHOUT_USER_ACTION があること
                //   2. そのパッケージを入れたのがこのアプリ自身であること(installer of record)
                // adb や別の経路で入れたアプリの初回は必ず確認画面が出る。
                // 一度このアプリ経由で入れ直せば、以降の更新は無確認になる。
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14 以降は更新の所有権も要る。取っておくと次回から無確認で更新できる。
                // 自作アプリは Play ストアから更新されないので、所有権を持って困ることはない。
                setRequestUpdateOwnership(true)
            }
        }
        val sessionId = installer.createSession(params)
        // commit より先に結果の受け口を用意する。ここが遅れると通知を取りこぼす。
        val awaited = InstallEvents.register(sessionId)
        onSession(sessionId)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(apk.name, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(InstallResultReceiver.ACTION)
                    .putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Throwable) {
            InstallEvents.forget(sessionId)
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
        try {
            awaited.await()
        } finally {
            InstallEvents.forget(sessionId)
        }
    }

    /** 確認ダイアログをもう一度開くための Intent。無ければ null。 */
    fun confirmIntentFor(sessionId: Int): Intent? = InstallEvents.confirmIntentFor(sessionId)
}

/**
 * 受信機と呼び出し側の橋渡し。プロセス内シングルトン。
 *
 * セッションごとに [CompletableDeferred] を持つ。Flow ではなくこれを使うのは、
 * 購読の前後という時間の問題を無くすため。登録前に結果が来た場合も [pending] に置いて
 * 次の [register] で拾えるようにしてある。
 */
object InstallEvents {
    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<ApkInstaller.Result>>()
    private val pending = ConcurrentHashMap<Int, ApkInstaller.Result>()
    private val confirmIntents = ConcurrentHashMap<Int, Intent>()

    /** commit の前に呼ぶ。返した Deferred が結果を受け取る。 */
    fun register(sessionId: Int): CompletableDeferred<ApkInstaller.Result> {
        val deferred = CompletableDeferred<ApkInstaller.Result>()
        val already = pending.remove(sessionId)
        if (already != null) {
            deferred.complete(already)
        } else {
            waiters[sessionId] = deferred
        }
        return deferred
    }

    /** セッションの後始末。待ち受けと保持した Intent を捨てる。 */
    fun forget(sessionId: Int) {
        waiters.remove(sessionId)
        pending.remove(sessionId)
        confirmIntents.remove(sessionId)
    }

    fun emitResult(sessionId: Int, result: ApkInstaller.Result) {
        confirmIntents.remove(sessionId)
        val waiter = waiters.remove(sessionId)
        if (waiter != null) waiter.complete(result) else pending[sessionId] = result
    }

    /**
     * OS が確認画面を要求してきた。Intent は保持する。
     * バックグラウンドからの Activity 起動は OS に弾かれることがあり、
     * そのとき一度きりの通知だと開き直す手段が無くなるため。
     */
    fun emitUserAction(sessionId: Int, intent: Intent) {
        confirmIntents[sessionId] = intent
        onUserAction?.invoke(sessionId, intent)
    }

    fun confirmIntentFor(sessionId: Int): Intent? = confirmIntents[sessionId]

    /** 確認画面の要求を受け取る先。ViewModel が設定する。 */
    @Volatile
    var onUserAction: ((Int, Intent) -> Unit)? = null
}
