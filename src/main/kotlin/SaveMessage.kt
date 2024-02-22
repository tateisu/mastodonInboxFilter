import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("SaveMessage")

class SaveMessage(
    val type: String,
    val time: String,
    val body: ByteArray,
    val headers: List<Pair<String, String>>,
    val headersExtra: List<Pair<String, String>>,
)

fun saveMessageTimeStr() = LocalDateTime.now().let {
    "%d%02d%02d-%02d%02d%02d.%03d".format(
        it.year,
        it.monthValue,
        it.dayOfMonth,
        it.hour,
        it.minute,
        it.second,
        it.nano /1_000_000,
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
            val headerText = buildString {
                record.headersExtra.forEach {
                    append("${it.first}: ${it.second}\n")
                }
                record.headers.forEach {
                    append("${it.first}: ${it.second}\n")
                }
            }
            val name = "${record.time}-${++idx}-${record.type}"
            File(recordDir, "$name.header").writeText(headerText)
            File(recordDir, "$name.body").writeBytes(record.body)
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
