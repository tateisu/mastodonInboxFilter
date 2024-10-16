import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import util.decodeJsonObject
import java.io.File

private val log = LoggerFactory.getLogger("InboxProxy")

val skipHeaders = listOf(
    "Transfer-Encoding",
).map { it.lowercase() }.toSet()

suspend fun PipelineContext<Unit, ApplicationCall>.inboxProxy(
    httpClient: HttpClient,
    saveMessageChannel: Channel<SaveMessage>,
    cacheDataDir: File,
    cacheErrorDir: File,
    config: Config,
) {
    val t = saveMessageTimeStr()

    val requestMethod = call.request.httpMethod
    val requestUri = call.request.uri
    log.info("(inboxProxy ${requestMethod.value} $requestUri")
    val incomingBody = call.receive<ByteArray>()
    val incomingHeaders = call.request.headers.toSaveHeaders()

    val extraHeaders = SaveMessage.SaveHeaders().apply {
        add("Request" to "$requestMethod $requestUri")
    }

    val saveMessage = SaveMessage(
        time = t,
        extraHeaders = extraHeaders,
        requestHeaders = incomingHeaders,
        requestBody = incomingBody,
    )
    try {
        // spamチェック
        try {
            val apStatus = incomingBody.decodeToString().decodeJsonObject().toAPStatus(debugPrefix = t)
            if (apStatus != null) {
                val isSpam = isSpam(
                    status = apStatus,
                    cacheDataDir = cacheDataDir,
                    cacheErrorDir = cacheErrorDir,
                    httpClient = httpClient,
                    config = config,
                )
                if (isSpam) {
                    call.respondText(
                        status = HttpStatusCode.Accepted,
                        contentType = ContentType.Text.Plain,
                        text = "automatic spam detection.",
                    )
                    return
                }
            }
        } catch (ex: Throwable) {
            log.warn("can't check spam.", ex)
        }

        // Mastodonサーバに投げる
        val originalResponse = httpClient.request {
            method = requestMethod
            url(config.redirectUrl + requestUri)
            for (pair in incomingHeaders) {
                when {
                    skipHeaders.contains(pair.first.lowercase()) -> Unit
                    else -> header(pair.first, pair.second)
                }
            }
            setBody(incomingBody)
        }
        val outgoingBody = originalResponse.readBytes()
        val outgoingHeaders = originalResponse.headers.toSaveHeaders()

        extraHeaders.add("Status" to originalResponse.status.toString())
        saveMessage.responseHeaders = outgoingHeaders
        saveMessage.responseBody = outgoingBody

        // Mastodonからの応答を呼び出し元に返す
        for (pair in outgoingHeaders) {
            when {
                pair.first == HttpHeaders.ContentType -> Unit
                pair.first == HttpHeaders.ContentLength -> Unit
                skipHeaders.contains(pair.first.lowercase()) -> Unit
                else -> call.response.header(pair.first, pair.second)
            }
        }
        when (originalResponse.status) {
            HttpStatusCode.Accepted,
            HttpStatusCode.NoContent,
            -> call.respond(originalResponse.status)

            else -> call.respondBytes(
                contentType = originalResponse.contentType(),
                status = originalResponse.status,
                provider = { outgoingBody },
            )
        }
        log.info(")inboxProxy ${requestMethod.value} $requestUri")
    } finally {
        try {
            saveMessageChannel.send(saveMessage)
        } catch (ex: Throwable) {
            log.warn("can't post SaveMessage.", ex)
        }
    }
}
