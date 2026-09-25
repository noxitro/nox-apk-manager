package com.noxitro.apkmanager.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * サービスアカウントの秘密鍵で Drive のアクセストークンを取る。
 *
 * ユーザー認証(OAuth の同意画面)を通らないので、
 * - Google Play 開発者サービスが要らない(Meta Quest のような GMS 非搭載端末でも動く)
 * - 同意画面の公開ステータスが「テスト」のときの **refresh token 7 日失効**に当たらない
 *
 * 代わりに、読ませたいフォルダをサービスアカウントのアドレスに共有しておく必要がある
 * (`docs/SETUP.md`)。共有は閲覧者で足りる。
 *
 * 手順は RFC 7523 の JWT bearer。秘密鍵で署名した JWT をトークンエンドポイントに渡すと
 * アクセストークン(1 時間)が返る。refresh token は無く、切れたら署名し直すだけ。
 */
class ServiceAccountAuth(
    private val client: OkHttpClient,
    private val keyJsonProvider: suspend () -> String?,
    /** テストから差し替えるためだけの口。本番は既定値。 */
    private val tokenUrl: String = TOKEN_URL,
) {

    /** 鍵がまだ端末に入っていない。設定の手順を出す合図。 */
    class MissingKey : IOException("サービスアカウントの鍵が設定されていません")

    @Serializable
    private data class KeyJson(
        @SerialName("client_email") val clientEmail: String,
        @SerialName("private_key") val privateKey: String,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long = 3600,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private var cachedToken: String? = null
    private var expiresAtMillis: Long = 0

    /** 鍵の持ち主。設定画面に出す。鍵が無ければ null。 */
    suspend fun clientEmail(): String? =
        keyJsonProvider()?.let { runCatching { json.decodeFromString<KeyJson>(it).clientEmail }.getOrNull() }

    /**
     * アクセストークン。期限内ならキャッシュを返す。
     * [forceRefresh] は Drive が 401/403 を返したときに呼ぶ。
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String = mutex.withLock {
        val now = System.currentTimeMillis()
        // 期限の 60 秒前から取り直す(通信の途中で切れないように)
        if (!forceRefresh) {
            cachedToken?.let { if (now < expiresAtMillis - 60_000) return it }
        }
        val raw = keyJsonProvider() ?: throw MissingKey()
        val key = runCatching { json.decodeFromString<KeyJson>(raw) }.getOrElse {
            throw IOException("サービスアカウントの JSON を読めません(client_email / private_key がありません)")
        }
        val fetched = requestToken(key)
        cachedToken = fetched.accessToken
        expiresAtMillis = now + fetched.expiresIn * 1000
        fetched.accessToken
    }

    fun invalidate() {
        cachedToken = null
        expiresAtMillis = 0
    }

    private suspend fun requestToken(key: KeyJson): TokenResponse = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = """{"iss":"${key.clientEmail}","scope":"$SCOPE","aud":"$tokenUrl","exp":${now + 3600},"iat":$now}"""
        val signingInput = "${header.b64url()}.${claims.b64url()}"
        val assertion = "$signingInput.${sign(signingInput, key.privateKey).b64url()}"

        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", assertion)
            .build()
        val request = Request.Builder().url(tokenUrl).post(body).build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // よくあるのは、鍵の失効・Drive API が無効・端末の時計のずれ(JWT の iat/exp が弾かれる)
                throw IOException("トークンを取得できません(HTTP ${resp.code}): ${text.take(300)}")
            }
            json.decodeFromString<TokenResponse>(text)
        }
    }

    /** PEM(PKCS#8)を読んで SHA256withRSA で署名する。 */
    private fun sign(input: String, pem: String): ByteArray {
        val der = Base64.getDecoder().decode(
            pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("""\s"""), ""),
        )
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        return Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(input.toByteArray(Charsets.UTF_8))
            sign()
        }
    }

    private fun String.b64url(): String = toByteArray(Charsets.UTF_8).b64url()

    private fun ByteArray.b64url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    }
}
