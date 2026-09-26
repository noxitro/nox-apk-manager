package com.noxitro.apkmanager.ui

import com.noxitro.apkmanager.ui.SetupStatus.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接続方法の画面に出す「今の状態」。
 *
 * 守るのは「分からないものを未と決めつけない」こと。
 * 共有は読んでみるまで分からないので、鍵が無い・通信に失敗したときに「共有されていません」と出すと、
 * 共有し直しても直らない問題に利用者を向かわせてしまう。
 */
class SetupStatusTest {

    private val email = "reader@example.iam.gserviceaccount.com"

    @Test
    fun `鍵が無ければ鍵は未、共有は分からない`() {
        val s = SetupStatus.of(null, SyncState.Error("未設定", needsKey = true), canInstallPackages = true)
        assertEquals(Check.TODO, s.key)
        assertEquals(Check.UNKNOWN, s.share)
    }

    @Test
    fun `鍵があって builds が見えなければ共有が未`() {
        val s = SetupStatus.of(email, SyncState.Error("見えません", needsShare = true), canInstallPackages = true)
        assertEquals(Check.DONE, s.key)
        assertEquals(Check.TODO, s.share)
    }

    @Test
    fun `読めていれば鍵と共有は済み`() {
        val s = SetupStatus.of(email, SyncState.Ready("12:00"), canInstallPackages = true)
        assertEquals(SetupStatus(Check.DONE, Check.DONE, Check.DONE), s)
        assertTrue(s.allDone)
    }

    @Test
    fun `通信の失敗では共有を未と決めつけない`() {
        val s = SetupStatus.of(email, SyncState.Error("通信に失敗しました"), canInstallPackages = true)
        assertEquals(Check.UNKNOWN, s.share)
    }

    @Test
    fun `読み込み中は分からない`() {
        val s = SetupStatus.of(null, SyncState.Loading, canInstallPackages = true)
        assertEquals(Check.UNKNOWN, s.key)
        assertEquals(Check.UNKNOWN, s.share)
    }

    @Test
    fun `インストールが許可されていなければ終わっていない`() {
        val s = SetupStatus.of(email, SyncState.Ready("12:00"), canInstallPackages = false)
        assertEquals(Check.TODO, s.install)
        assertFalse(s.allDone)
    }
}
