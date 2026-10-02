package com.noxitro.apkmanager.drive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drive の 403 の見分け。
 *
 * Drive API が無効なときも 403 になる。これを「builds/ が共有されていない」と出すと、
 * 利用者は共有をやり直し続けて直らない。本文(Google のエラー JSON)で見分ける。
 */
class DriveExceptionTest {

    /** Drive API を有効にしていないプロジェクトの鍵で読んだときに返る本文(先頭 500 文字)。 */
    private val apiDisabledBody = """{
  "error": {
    "code": 403,
    "message": "Google Drive API has not been used in project 123456789012 before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/drive.googleapis.com/overview?project=123456789012 then retry. If you enabled this API recently, wait a few minutes for the action to propagate to our systems and retry.",
    "errors": [
      {
        "message": "Google Drive API has not been used""".take(500)

    @Test
    fun `Drive API が無効な 403 を見分ける`() {
        assertTrue(DriveException(403, apiDisabledBody).isApiDisabled)
        assertTrue(DriveException(403, """{"error":{"errors":[{"reason":"accessNotConfigured"}]}}""").isApiDisabled)
    }

    @Test
    fun `それ以外の 403 や別の番号は API 無効としない`() {
        assertFalse(DriveException(403, """{"error":{"errors":[{"reason":"insufficientFilePermissions"}]}}""").isApiDisabled)
        assertFalse(DriveException(404, apiDisabledBody).isApiDisabled)
    }
}
