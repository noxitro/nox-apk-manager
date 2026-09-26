package com.noxitro.apkmanager.ui

/**
 * Drive につなぐのに要る 3 つの準備と、それぞれが済んでいるか。接続方法の画面の先頭に出す。
 *
 * 1. 鍵(サービスアカウントの JSON)が入っている
 * 2. Drive の builds/ がそのサービスアカウントに共有されている
 * 3. このアプリからのインストールが許可されている
 *
 * 2 は実際に読んでみるまで分からない。鍵が無い・通信に失敗した等で読めていないときは
 * 「未」と決めつけず [Check.UNKNOWN] にする(違う手順に誘導しないため)。
 */
data class SetupStatus(val key: Check, val share: Check, val install: Check) {

    enum class Check { DONE, TODO, UNKNOWN }

    val allDone: Boolean get() = key == Check.DONE && share == Check.DONE && install == Check.DONE

    companion object {
        fun of(serviceAccountEmail: String?, sync: SyncState, canInstallPackages: Boolean): SetupStatus {
            val key = when {
                // 読めているなら鍵は足りている(debug の端末内フォルダ読みは鍵が要らない)
                serviceAccountEmail != null || sync is SyncState.Ready -> Check.DONE
                sync is SyncState.Loading || sync is SyncState.Idle -> Check.UNKNOWN
                else -> Check.TODO
            }
            val share = when {
                sync is SyncState.Ready -> Check.DONE
                sync is SyncState.Error && sync.needsShare -> Check.TODO
                else -> Check.UNKNOWN
            }
            val install = if (canInstallPackages) Check.DONE else Check.TODO
            return SetupStatus(key, share, install)
        }
    }
}
