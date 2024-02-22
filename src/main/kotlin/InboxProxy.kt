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
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
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
    log.debug("inboxProxy ${requestMethod.value} $requestUri")
    val incomingBody = call.receive<ByteArray>()
    val incomingHeaders = buildList {
        for (entry in call.request.headers.entries()) {
            for (value in entry.value) {
                add(Pair(entry.key, value))
            }
        }
    }

    try {
        saveMessageChannel.send(
            SaveMessage(
                type = "request",
                time = t,
                body = incomingBody,
                headers = incomingHeaders,
                headersExtra = listOf(
                    "Request" to "$requestMethod $requestUri",
                )
            )
        )
    } catch (ex: Throwable) {
        log.warn("can't post SaveMessage.", ex)
    }

    try {
        val apStatus = incomingBody.decodeToString().toAPStatus(debugPrefix = t)
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

    // Mastodonサーバに投げて
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
    val outgoingHeaders = buildList {
        for (entry in originalResponse.headers.entries()) {
            for (value in entry.value) {
                add(Pair(entry.key, value))
            }
        }
    }

    // 応答を呼び出し元に返す
    for (pair in outgoingHeaders) {
        when {
            pair.first == HttpHeaders.ContentType -> Unit
            skipHeaders.contains(pair.first.lowercase()) -> Unit
            else -> call.response.header(pair.first, pair.second)
        }
    }
    call.respondBytes(
        contentType = originalResponse.contentType(),
        status = originalResponse.status
    ) {
        outgoingBody
    }


    try {
        saveMessageChannel.send(
            SaveMessage(
                type = "response",
                time = t,
                body = outgoingBody,
                headers = outgoingHeaders,
                headersExtra = listOf(
                    "Status" to "${originalResponse.status}",
                )
            )
        )
    } catch (ex: Throwable) {
        log.warn("can't post SaveMessage.", ex)
    }
}
