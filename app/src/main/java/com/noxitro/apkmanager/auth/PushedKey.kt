package com.noxitro.apkmanager.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * `adb push` で置かれた鍵を探して中身を確かめる。Android に依存しないので単体テストできる。
 *
 * 以前は「決まった名前のファイルが無い / 読めない / private_key が無い」をすべて黙って無視していたため、
 * 端末には「鍵が未設定」としか出ず、push したのに何が悪いのか分からなかった。
 * ここでは見つけたもの・弾いた理由をそのまま返し、画面に出す。
 */
object PushedKey {

    sealed interface Result {
        /** 取り込める鍵があった。[json] は文字コードを直した後の中身。 */
        data class Found(val file: File, val json: String) : Result

        /** 鍵らしいファイルが無い。[others] は同じ場所にあったもの(置き場所・名前の間違いに気づくため)。 */
        data class NotFound(val dir: File?, val others: List<String>) : Result

        /** ファイルはあったが鍵として使えない。 */
        data class Invalid(val file: File, val reason: String) : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [dir] から鍵を探す。まず [preferredName]、無ければ同じ場所の `*.json`
     * (Cloud Console がつけた名前のまま push した場合を拾う)。
     */
    fun inspect(dir: File?, preferredName: String): Result {
        if (dir == null || !dir.isDirectory) return Result.NotFound(dir, emptyList())
        val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        val preferred = files.firstOrNull { it.name == preferredName }
        val candidates = listOfNotNull(preferred) +
            files.filter { it != preferred && it.name.endsWith(".json", ignoreCase = true) }
        if (candidates.isEmpty()) {
            return Result.NotFound(dir, dir.listFiles().orEmpty().map { if (it.isDirectory) it.name + "/" else it.name }.sorted())
        }

        var firstInvalid: Result.Invalid? = null
        for (file in candidates) {
            val result = check(file)
            if (result is Result.Found) return result
            if (firstInvalid == null) firstInvalid = result as Result.Invalid
        }
        return firstInvalid!!
    }

    private fun check(file: File): Result {
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return Result.Invalid(file, "ファイルを読めません(${it.message ?: it.javaClass.simpleName})")
        }
        if (bytes.isEmpty()) return Result.Invalid(file, "中身が空です")
        val text = decode(bytes)
        val reason = problemOf(text) ?: return Result.Found(file, text)
        return Result.Invalid(file, reason)
    }

    /**
     * UTF-8(BOM 付きを含む)と UTF-16 を読む。
     * Windows PowerShell 5.1 の `>` や `Out-File` で作り直した JSON は UTF-16 になり、
     * UTF-8 として読むと 1 文字ごとに NUL が挟まって "private_key" が見つからない。
     */
    fun decode(bytes: ByteArray): String {
        fun b(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF)
        val text = when {
            b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            b(0) == 0xFF && b(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            b(0) == 0xFE && b(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            // BOM 無しの UTF-16。JSON は ASCII の '{' で始まるので、2 バイト目が NUL なら LE
            bytes.size >= 2 && b(0) != 0 && b(1) == 0 -> String(bytes, Charsets.UTF_16LE)
            bytes.size >= 2 && b(0) == 0 && b(1) != 0 -> String(bytes, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
        return text.trimStart('﻿')
    }

    /** サービスアカウントの鍵として使えないなら理由。使えるなら null。 */
    fun problemOf(text: String): String? {
        val obj: JsonObject = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            return "JSON として読めません(途中で切れている・別のファイルの可能性)"
        }
        // 廃止した旧方式(OAuth クライアント)の JSON を送ってしまう取り違えが起こりやすい
        if ("installed" in obj || "web" in obj) {
            return "OAuth クライアントの JSON です。サービスアカウントの「キー」タブで作った JSON を送ってください"
        }
        val missing = listOf("client_email", "private_key").filter { it !in obj }
        if (missing.isNotEmpty()) {
            return "サービスアカウントの鍵ではありません(${missing.joinToString(" / ")} がありません)"
        }
        return null
    }
}
