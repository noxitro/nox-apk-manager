package com.noxitro.apkmanager.auth

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * サービスアカウント認証の単体テスト。
 *
 * 守るのは「Google に何を送っているか」。JWT の署名と中身が壊れると、
 * 症状は端末側で `invalid_grant` としか出ず、原因が読めない。
 * 鍵はテストの中で作るので、本物の秘密鍵はリポジトリにもテストにも入らない。
 */
class ServiceAccountAuthTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private val keyJson: String
        get() {
            val pem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder().encodeToString(keyPair.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"
            return """{"client_email":"reader@example.iam.gserviceaccount.com","private_key":${quote(pem)}}"""
        }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n") + "\""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun auth(keyProvider: suspend () -> String?) =
        ServiceAccountAuth(client, keyProvider, server.url("/token").toString())

    private fun enqueueToken(token: String, expiresIn: Long = 3600) {
        server.enqueue(MockResponse().setBody("""{"access_token":"$token","expires_in":$expiresIn}"""))
    }

    @Test
    fun `鍵が無ければ MissingKey`() = runTest {
        val e = runCatching { auth { null }.accessToken() }.exceptionOrNull()
        assertTrue("MissingKey ではなく $e", e is ServiceAccountAuth.MissingKey)
        assertEquals("通信してはいけない", 0, server.requestCount)
    }

    @Test
    fun `壊れた鍵は MissingKey ではなく IOException`() = runTest {
        val e = runCatching { auth { "{}" }.accessToken() }.exceptionOrNull()
        assertTrue("IOException ではなく $e", e is IOException)
        assertTrue("MissingKey にしてはいけない(鍵はあるが読めない)", e !is ServiceAccountAuth.MissingKey)
    }

    @Test
    fun `送る JWT は自分の鍵で検証でき、iss scope aud が揃っている`() = runTest {
        enqueueToken("tok-1")
        val subject = auth { keyJson }
        assertEquals("tok-1", subject.accessToken())

        val body = server.takeRequest().body.readUtf8()
        val assertion = body.split("&").first { it.startsWith("assertion=") }
            .removePrefix("assertion=").let { java.net.URLDecoder.decode(it, "UTF-8") }

        val (header, claims, signature) = assertion.split(".")
        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(keyPair.public)
            update("$header.$claims".toByteArray())
            verify(Base64.getUrlDecoder().decode(signature))
        }
        assertTrue("署名を自分の公開鍵で検証できない", verified)

        val decoded = String(Base64.getUrlDecoder().decode(claims))
        assertTrue("iss がサービスアカウントでない: $decoded", decoded.contains("reader@example.iam.gserviceaccount.com"))
        assertTrue("scope が drive.readonly でない: $decoded", decoded.contains(ServiceAccountAuth.SCOPE))
        assertTrue("aud がトークンエンドポイントでない: $decoded", decoded.contains(server.url("/token").toString()))
    }

    @Test
    fun `期限内は取り直さない。forceRefresh のときだけ取り直す`() = runTest {
        enqueueToken("tok-1")
        enqueueToken("tok-2")
        val subject = auth { keyJson }

        assertEquals("tok-1", subject.accessToken())
        assertEquals("キャッシュを返すべき", "tok-1", subject.accessToken())
        assertEquals(1, server.requestCount)

        assertEquals("tok-2", subject.accessToken(forceRefresh = true))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `残り 1 分を切った token は自分で取り直す`() = runTest {
        enqueueToken("tok-1", expiresIn = 30)
        enqueueToken("tok-2", expiresIn = 3600)
        val subject = auth { keyJson }

        assertEquals("tok-1", subject.accessToken())
        // 期限の 60 秒前から取り直すので、30 秒しか無いトークンは即座に作り直される
        assertEquals("tok-2", subject.accessToken())
    }

    @Test
    fun `トークンエンドポイントのエラーは本文つきで投げる`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        val e = runCatching { auth { keyJson }.accessToken() }.exceptionOrNull()
        assertTrue(e is IOException)
        assertTrue("原因が読めない: ${e?.message}", e!!.message!!.contains("invalid_grant"))
    }

    @Test
    fun `clientEmail は鍵から読む。鍵が無ければ null`() = runTest {
        assertEquals("reader@example.iam.gserviceaccount.com", auth { keyJson }.clientEmail())
        assertNull(auth { null }.clientEmail())
        assertNull("壊れた鍵でも落とさない", auth { "{}" }.clientEmail())
    }
}
