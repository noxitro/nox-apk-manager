package com.noxitro.apkmanager.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** PackageInstaller のセッション結果を受けて [InstallEvents] に流す。 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    InstallEvents.emitUserAction(sessionId, confirm)
                } else {
                    InstallEvents.emitResult(
                        sessionId,
                        ApkInstaller.Result.Failure(status, "確認 Intent が無い", packageName),
                    )
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                InstallEvents.emitResult(sessionId, ApkInstaller.Result.Success(packageName))
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                InstallEvents.emitResult(
                    sessionId,
                    ApkInstaller.Result.Aborted(
                        message = message,
                        // 公開の定数は無いが、OS は古い形の失敗コードもこの名前で載せてくる。
                        legacyStatus = intent.getIntExtra(EXTRA_LEGACY_STATUS, 0).takeIf { it != 0 },
                    ),
                )
            else ->
                InstallEvents.emitResult(sessionId, ApkInstaller.Result.Failure(status, message, packageName))
        }
    }

    companion object {
        const val ACTION = "com.noxitro.apkmanager.INSTALL_RESULT"
        const val EXTRA_SESSION_ID = "session_id"

        /** `PackageInstaller.EXTRA_LEGACY_STATUS`(隠し API)と同じ名前。 */
        private const val EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS"
    }
}
