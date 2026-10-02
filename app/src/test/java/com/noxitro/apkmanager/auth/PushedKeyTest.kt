package com.noxitro.apkmanager.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * push された鍵の取り込み判定。
 *
 * 守るのは「取り込めないときに理由が出ること」。以前は黙って無視していたため、
 * 端末には「鍵が未設定」としか出ず、push したのに何が悪いのか分からなかった。
 */
class PushedKeyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = """{"type":"service_account","client_email":"r@x.iam.gserviceaccount.com","private_key":"-----BEGIN PRIVATE KEY-----\nAAA\n-----END PRIVATE KEY-----\n"}"""

    @Test
    fun `決まった名前の鍵を取り込む`() {
        tmp.newFile(NAME).writeText(key)
        val r = PushedKey.inspect(tmp.root, NAME)
        assertTrue(r is PushedKey.Result.Found)
        assertEquals(key, (r as PushedKey.Result.Found).json)
    }

    @Test
    fun `Cloud Console がつけた名前のまま push しても拾う`() {
        tmp.newFile("nox-apk-manager-1a2b3c4d5e6f.json").writeText(key)
        val r = PushedKey.inspect(tmp.root, NAME)
        assertTrue(r is PushedKey.Result.Found)
        assertEquals("nox-apk-manager-1a2b3c4d5e6f.json", (r as PushedKey.Result.Found).file.name)
    }

    @Test
    fun `UTF-16 と BOM 付き UTF-8 を読める`() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + key.toByteArray(Charsets.UTF_16LE)
        assertEquals(key, PushedKey.decode(utf16))
        assertEquals(key, PushedKey.decode(key.toByteArray(Charsets.UTF_16LE)))
        val bomUtf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + key.toByteArray()
        assertEquals(key, PushedKey.decode(bomUtf8))

        tmp.newFile(NAME).writeBytes(utf16)
        val r = PushedKey.inspect(tmp.root, NAME)
        assertEquals(key, (r as PushedKey.Result.Found).json)
    }

    @Test
    fun `OAuth クライアントの JSON は理由つきで弾く`() {
        tmp.newFile(NAME).writeText("""{"installed":{"client_id":"x","client_secret":"y"}}""")
        val r = PushedKey.inspect(tmp.root, NAME)
        assertTrue(r is PushedKey.Result.Invalid)
        assertTrue((r as PushedKey.Result.Invalid).reason.contains("OAuth"))
    }

    @Test
    fun `private_key が無ければ理由つきで弾く`() {
        tmp.newFile(NAME).writeText("""{"client_email":"r@x"}""")
        val r = PushedKey.inspect(tmp.root, NAME) as PushedKey.Result.Invalid
        assertTrue(r.reason.contains("private_key"))
    }

    @Test
    fun `壊れた鍵より使える鍵を優先する`() {
        tmp.newFile(NAME).writeText("{broken")
        tmp.newFile("other.json").writeText(key)
        val r = PushedKey.inspect(tmp.root, NAME)
        assertEquals("other.json", (r as PushedKey.Result.Found).file.name)
    }

    @Test
    fun `鍵が無ければ置いてあるものを返す`() {
        tmp.newFolder("builds")
        tmp.newFile("nox-drive-sa.json.txt").writeText(key)
        val r = PushedKey.inspect(tmp.root, NAME) as PushedKey.Result.NotFound
        assertEquals(listOf("builds/", "nox-drive-sa.json.txt"), r.others)
    }

    @Test
    fun `ファイル選択で渡された中身を確かめる`() {
        assertEquals(key, PushedKey.parse(key.toByteArray()).getOrNull())
        assertEquals("中身が空です", PushedKey.parse(ByteArray(0)).exceptionOrNull()?.message)
        assertTrue(PushedKey.parse("""{"web":{}}""".toByteArray()).exceptionOrNull()!!.message!!.contains("OAuth"))
    }

    private companion object {
        const val NAME = "nox-drive-sa.json"
    }
}
