import io.github.xn32.json5k.Json5
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.test.DefaultAsserter.assertTrue

class APStatusTest {
    companion object {
        private val log = LoggerFactory.getLogger("APStatusTest")
    }

    @Test
    fun apParseTest() = runTest {
        val configFile = File("./config.json5")
        val config: Config = Json5.decodeFromString(configFile.readText())
        val cacheDir = File("./testCacheDir")
        val cacheDataDir = File(cacheDir, "data")
        val cacheErrorDir = File(cacheDir, "error")

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
                    file.readText().toAPStatus(warnPrefix = file.name)
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
}
