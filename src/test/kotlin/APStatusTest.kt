import io.github.xn32.json5k.Json5
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.http.encodeURLQueryComponent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.slf4j.LoggerFactory
import util.decodeJsonObject
import java.io.File
import kotlin.test.DefaultAsserter.assertTrue
import kotlin.test.assertTrue

class APStatusTest {
    companion object {
        private val log = LoggerFactory.getLogger("APStatusTest")
    }

    /**
     * 実際に動かしてrecordフォルダに記録されたリクエストのJSONデータを読んでみる
     * - 例外がでたらNG
     * - SPAMが含まれるはず
     */
    @Test
    fun apParseTest() = runTest {
        val configFile = File("./config.json5")
        val config: Config = Json5.decodeFromString(configFile.readText())
        val cacheDir = File("./testCacheDir")
        val cacheDataDir = File(cacheDir, "data")
        val cacheErrorDir = File(cacheDir, "error")
        cacheDataDir.mkdirs()
        cacheErrorDir.mkdirs()

        HttpClient(CIO) {
            install(UserAgent) {
                agent = config.userAgent
            }
            install(HttpTimeout) {
                socketTimeoutMillis = config.requestTimeoutMs
                requestTimeoutMillis = config.requestTimeoutMs
                connectTimeoutMillis = config.requestTimeoutMs
            }
        }.use { httpClient ->
            var spamCount = 0
            for (file in File("apParseTest").listFiles() ?: emptyArray()) {
                if (!file.name.endsWith("-request.body")) continue
                val status = assertDoesNotThrow("file $file") {
                    file.readText().decodeJsonObject().toAPStatus(debugPrefix = file.name)
                }
                status ?: continue
                val isSpam = isSpam(
                    status = status,
                    cacheDataDir = cacheDataDir,
                    cacheErrorDir = cacheErrorDir,
                    httpClient = httpClient,
                    config = config,
                )
                if (isSpam) ++spamCount
            }
            assertTrue(
                "some messages are spam. $spamCount",
                spamCount > 0
            )
        }
    }

    /**
     * actor URL から acct を得るには追加のHTTPリクエストが必要
     * - 410 Gone が返る場合もある
     */
    @Test
    fun actorTest() = runTest {
        assertTrue(true)

        val configFile = File("./config.json5")
        val config: Config = Json5.decodeFromString(configFile.readText())
        val cacheDir = File("./testCacheDir")
        val cacheDataDir = File(cacheDir, "data")
        val cacheErrorDir = File(cacheDir, "error")
        cacheDataDir.mkdirs()
        cacheErrorDir.mkdirs()

        HttpClient(CIO) {
            install(UserAgent) {
                agent = config.userAgent
            }
            install(HttpTimeout) {
                socketTimeoutMillis = config.requestTimeoutMs
                requestTimeoutMillis = config.requestTimeoutMs
                connectTimeoutMillis = config.requestTimeoutMs
            }
        }.use { httpClient ->
            File("apParseTest").listFiles()
                ?.filter {
                    it.name.endsWith("-request.body")
                }
                ?.mapNotNull { file ->
                    file.readText().decodeJsonObject().string("actor")
                }?.toSet()?.toList()?.sorted()?.forEach { actor ->
                    val host = """https://([^/]+)""".toRegex().find(actor)?.groupValues?.elementAtOrNull(1)
                    val webFingerUrl = "https://$host/.well-known/webfinger?resource=${actor.encodeURLQueryComponent()}"
                    val root = try {
                        cachedGet(
                            cacheDataDir = cacheDataDir,
                            cacheErrorDir = cacheErrorDir,
                            httpClient = httpClient,
                            url = webFingerUrl,
                        ).decodeToString().decodeJsonObject()
                    } catch (ex: Throwable) {
                        when {
                            ex.message?.contains("410 Gone") == true -> null
                            else -> throw ex
                        }
                    } ?: return@forEach
                    val acct = root.string("subject")?.let {
                        when {
                            it.startsWith("acct:") -> it.substring(5)
                            else -> null
                        }
                    }?: error("missing acct in $root")
                    println("acct=$acct")
                }
        }
    }
}
