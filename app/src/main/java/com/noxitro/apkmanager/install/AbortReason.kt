package com.noxitro.apkmanager.install

/**
 * OS がインストールを中止した理由を、利用者が次の一手を選べる文にする。
 *
 * 中止は「確認画面で取り消した」だけではない。セッションが途中で破棄されたときや、
 * 端末の保護機能が止めたときにも同じ `STATUS_FAILURE_ABORTED` で返る。区別しないと、
 * 押した覚えが無いのに「キャンセルされました」とだけ出て、何を直せばよいか分からない
 * (2026-10-06、実機で起きた)。
 *
 * 知っている形は言い換え、**知らない形も OS の文をそのまま添える**(捨てると次も分からない)。
 */
object AbortReason {

    /** `INSTALL_FAILED_ABORTED`。 */
    private const val LEGACY_ABORTED = -115

    fun describe(message: String, legacyStatus: Int?): String {
        val raw = message.trim()
        val plain = when {
            raw.contains("rejected", ignoreCase = true) ||
                raw.contains("User canceled", ignoreCase = true) ||
                raw.contains("User cancelled", ignoreCase = true) ->
                "確認画面で取り消されました(「キャンセル」を押したか、画面の外に触れて閉じた)。" +
                    "もう一度「更新」を押し、確認画面で「インストール」を押してください"
            raw.contains("abandon", ignoreCase = true) ->
                "インストールの途中で OS がセッションを破棄しました(アプリが閉じられた、メモリが足りない など)。" +
                    "もう一度「更新」を押してください"
            raw.contains("owner", ignoreCase = true) ->
                "このアプリの更新元が別のアプリになっています。端末の設定で、このアプリを入れた元を確かめてください"
            raw.contains("block", ignoreCase = true) || raw.contains("policy", ignoreCase = true) ->
                "端末の保護機能に止められました(Samsung のオートブロッカー、Play プロテクト など)。" +
                    "設定でこのアプリからのインストールを許可してください"
            raw.isEmpty() ->
                "OS は理由を返しませんでした。確認画面で取り消したときもこの形になります。" +
                    "確認画面が出なかったなら、Samsung のオートブロッカーや Play プロテクトを確かめてください"
            else -> null
        }
        val code = legacyStatus?.takeIf { it != LEGACY_ABORTED }?.let { " / code $it" }.orEmpty()
        val detail = raw.takeIf { it.isNotEmpty() }?.let { "(OS: $it$code)" }.orEmpty()
        return "キャンセルされました: " + (plain ?: "OS が中止を返しました") + detail
    }
}
