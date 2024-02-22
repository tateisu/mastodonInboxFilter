import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

suspend fun cachedGet(
    cacheDataDir: File,
    cacheErrorDir: File,
    httpClient: HttpClient,
    url: String,
    sizeLimit: Int? = null,
): ByteArray {
    val reBadChars = """[\x00-\x20?*:<>\\\/"|]"""
    val name = url.replace(reBadChars, "-")
    val fData = File(cacheDataDir, name)
    val fError = File(cacheErrorDir, name)
    if (fData.isFile) return fData.readBytes()
    if (fError.isFile) error(fData.readText())
    try {
        val res: HttpResponse = httpClient.request {
            expectSuccess = true
            method = HttpMethod.Get
            url(url)
        }
        val body = when {
            sizeLimit == null -> res.readBytes()
            else -> {
                val bao = ByteArrayOutputStream()
                val channel: ByteReadChannel = res.body()
                while (!channel.isClosedForRead) {
                    val maxRemain = sizeLimit - bao.size()
                    val packet = channel.readRemaining(maxRemain.toLong())
                    while (!packet.isEmpty) {
                        bao.writeBytes(packet.readBytes())
                    }
                }
                bao.toByteArray()
            }
        }
        fData.writeBytes(body)
        return body
    } catch (ex: Throwable) {
        val errText = "${ex.javaClass.simpleName} ${ex.message}"
        if (ex !is CancellationException) {
            fError.writeText(errText)
        }
        error(errText)
    }
}
