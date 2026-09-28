package com.noxitro.apkmanager.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 失敗した行に出す「次の一手」の判定。 */
class JobStateTest {

    private fun failed(needsUninstall: Boolean = false, targetPackage: String? = "com.noxitro.a", withBuild: Boolean = true) =
        JobState(
            stage = JobState.Stage.FAILED,
            message = "失敗",
            targetPackage = targetPackage,
            build = if (withBuild) testBuild() else null,
            needsUninstall = needsUninstall,
        )

    @Test
    fun `アンインストールが要る失敗にはアンインストールだけを出す`() {
        val job = failed(needsUninstall = true)
        assertTrue(job.canUninstall)
        // 入れ直しても同じ理由で弾かれるので、再試行は出さない
        assertFalse(job.canRetry)
    }

    @Test
    fun `それ以外の失敗には再試行を出す`() {
        val job = failed()
        assertTrue(job.canRetry)
        assertFalse(job.canUninstall)
    }

    @Test
    fun `入れようとしたビルドが分からなければ再試行を出さない`() {
        assertFalse(failed(withBuild = false).canRetry)
    }

    @Test
    fun `package 名が分からなければアンインストールを出さない`() {
        assertFalse(failed(needsUninstall = true, targetPackage = null).canUninstall)
    }

    @Test
    fun `進行中と完了にはどちらも出さない`() {
        for (stage in listOf(JobState.Stage.DOWNLOADING, JobState.Stage.INSTALLING, JobState.Stage.WAITING_USER, JobState.Stage.DONE)) {
            val job = JobState(stage = stage, message = "", targetPackage = "com.noxitro.a", build = testBuild(), needsUninstall = true)
            assertFalse("$stage", job.canUninstall)
            assertFalse("$stage", job.canRetry)
        }
    }
}
