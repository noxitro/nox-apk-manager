package com.noxitro.apkmanager.install

import org.junit.Assert.assertTrue
import org.junit.Test

/** OS が返した中止の理由を、次の一手が分かる文にする(0.7.1)。 */
class AbortReasonTest {

    @Test
    fun `確認画面で取り消したときはそう言う`() {
        val text = AbortReason.describe("INSTALL_FAILED_ABORTED: User rejected permissions", -115)
        assertTrue(text, text.contains("確認画面で取り消されました"))
        // OS の文は捨てずに添える。
        assertTrue(text, text.contains("User rejected permissions"))
        // 取り消しのコード(-115)は当たり前なので添えない。
        assertTrue(text, !text.contains("code -115"))
    }

    @Test
    fun `セッションが破棄されたときは取り消しと言わない`() {
        val text = AbortReason.describe("Session was abandoned", null)
        assertTrue(text, text.contains("セッションを破棄"))
        assertTrue(text, !text.contains("確認画面で取り消されました"))
    }

    @Test
    fun `保護機能に止められたときは設定を案内する`() {
        val text = AbortReason.describe("Install blocked by policy", -2)
        assertTrue(text, text.contains("保護機能"))
        assertTrue(text, text.contains("code -2"))
    }

    /** 理由が空でも、何を確かめればよいかは言う。 */
    @Test
    fun `理由が無いときも確かめる先を出す`() {
        val text = AbortReason.describe("", null)
        assertTrue(text, text.contains("理由を返しませんでした"))
        assertTrue(text, text.contains("オートブロッカー"))
    }

    /** 知らない形は言い換えられないが、OS の文はそのまま出す(次に原因を探す手がかり)。 */
    @Test
    fun `知らない理由はそのまま出す`() {
        val text = AbortReason.describe("INSTALL_FAILED_SOMETHING_NEW: whatever", -999)
        assertTrue(text, text.contains("INSTALL_FAILED_SOMETHING_NEW: whatever"))
        assertTrue(text, text.contains("code -999"))
    }
}
