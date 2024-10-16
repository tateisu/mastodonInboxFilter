import io.ktor.http.Headers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.slf4j.LoggerFactory
import util.notEmpty
import java.io.File
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("SaveMessage")

class SaveMessage(
    val time: String,
    val extraHeaders: SaveHeaders,
    val requestHeaders: SaveHeaders,
    val requestBody: ByteArray?,
    var responseHeaders: SaveHeaders? = null,
    var responseBody: ByteArray? = null,
) {
    class SaveHeaders : ArrayList<Pair<String, String>>()
}

fun Headers.toSaveHeaders() = SaveMessage.SaveHeaders().apply{
    for (entry in entries()) {
        for (value in entry.value) {
            add(Pair(entry.key, value))
        }
    }
}

fun saveMessageTimeStr() = LocalDateTime.now().let {
    "%d%02d%02d-%02d%02d%02d.%03d".format(
        it.year,
        it.monthValue,
        it.dayOfMonth,
        it.hour,
        it.minute,
        it.second,
        it.nano / 1_000_000,
    )
}

suspend fun consumeSaveMessage(
    recordDir: File,
    channel: Channel<SaveMessage>,
) {
    var idx = 0
    while (true) {
        try {
            val record = channel.receive()
            val name = "${record.time}-${++idx}"
            log.info("(save $name")
            val headerText = buildString {
                record.extraHeaders.forEach {
                    append("${it.first}: ${it.second}\n")
                }
                append("#####################\n")
                append("# Request\n")
                record.requestHeaders.forEach {
                    append("${it.first}: ${it.second}\n")
                }
                append("#####################\n")
                append("# Response\n")
                record.responseHeaders?.forEach {
                    append("${it.first}: ${it.second}\n")
                }
            }
            File(recordDir, "$name.headers").writeText(headerText)
            record.requestBody?.notEmpty()?.let {
                File(recordDir, "$name.request.body").writeBytes(it)
            }
            record.responseBody?.notEmpty()?.let {
                File(recordDir, "$name.response.body").writeBytes(it)
            }
            log.info(")save $name")
        } catch (ex: Throwable) {
            when (ex) {
                is CancellationException,
                is ClosedReceiveChannelException,
                -> break

                else -> log.error("collectRecordChannel failed.", ex)
            }
        }
    }
}
