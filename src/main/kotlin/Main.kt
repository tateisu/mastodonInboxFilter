import io.github.xn32.json5k.Json5
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.slf4j.LoggerFactory
import util.EmptyScope
import java.io.File
import java.lang.Thread.sleep
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

private val log = LoggerFactory.getLogger("Main")

fun main(args: Array<String>) {
    System.`in`.close()

    val myPid = ProcessHandle.current().pid()

    val parser = ArgParser("java -jar mastodonInboxFilter.jar")
    val configPath by parser.argument(
        ArgType.String,
        description = "config file"
    ).optional().default("config.json5")

    val configTest by parser.option(
        ArgType.Boolean,
        fullName = "configTest",
        description = "config test only."
    ).default(false)

    val autoReport by parser.option(
        ArgType.Boolean,
        fullName = "autoReport",
        description = "not run server, check logs and send DM to remote server admin.",
    ).default(false)

    val noPost by parser.option(
        ArgType.Boolean,
        fullName = "noPost",
        description = "(with autoReport)not post DMs, just get summary."
    ).default(false)

    val hours by parser.option(
        ArgType.Int,
        fullName = "hours",
        description = "(with autoReport)Check the logs going back to the specified number of hours."
    ).default(24)

    val testReport by parser.option(
        ArgType.String,
        fullName = "testReport",
        description = "(with autoReport)just send test report message to specified mention to."
    )

    parser.parse(args)

    val configFile = File(configPath)
    val config: Config = Json5.decodeFromString(configFile.readText())
    config.configFile = configFile

    if (configTest) return

    if (autoReport) {
        runBlocking {
            autoReport(
                config = config.autoReport
                    ?: error("config has no autoReport sub object."),
                noPost = noPost,
                hours = hours,
                testMentionTo = testReport,
            )
        }
        return
    }

    writePidFile(
        pidFile = File(config.pidFile),
        myPid = myPid,
    )
    log.info("program start. pid=$myPid")

    val recordDir = File(config.recordDir)
    val cacheDataDir = File("${config.cacheDir}/data")
    val cacheErrorDir = File("${config.cacheDir}/error")
    recordDir.mkdirs()
    cacheDataDir.mkdirs()
    cacheErrorDir.mkdirs()

    val httpClient = HttpClient(CIO) {
        install(UserAgent) {
            agent = config.userAgent
        }
        install(HttpTimeout) {
            socketTimeoutMillis = config.requestTimeoutMs
            requestTimeoutMillis = config.requestTimeoutMs
            connectTimeoutMillis = config.requestTimeoutMs
        }
    }
    val saveMessageChannel = Channel<SaveMessage>()
    val saveMessageJob = EmptyScope.launch(Dispatchers.IO) {
        consumeSaveMessage(
            recordDir = recordDir,
            channel = saveMessageChannel
        )
    }

    val listenPort = config.listenPort
    val listenHost = config.listenHost

    val server = embeddedServer(
        Netty,
        port = listenPort,
        host = listenHost,
    ) {
        routing {
            post("/inbox") {
                inboxProxy(
                    httpClient = httpClient,
                    saveMessageChannel = saveMessageChannel,
                    cacheDataDir = cacheDataDir,
                    cacheErrorDir = cacheErrorDir,
                    config = config,
                )
            }
        }
    }
    log.info("[${listenHost}:${listenPort}] server start...")
    server.start(wait = false)
    log.info("[${listenHost}:${listenPort}] server started.")

    Runtime.getRuntime().addShutdownHook(
        object : Thread() {
            override fun run() {
                // まずサーバの受付を止める
                log.info("[${listenHost}:${listenPort}] server stop...")
                server.stop(
                    gracePeriodMillis = 333L,
                    timeoutMillis = 1_000L,
                )
                log.info("[${listenHost}:${listenPort}] server stopped.")
                saveMessageChannel.close()
                // 実行中の添付データ取得を止める
                log.info("httpClient stop...")
                httpClient.close()
                log.info("httpClient stopped.")
                // 中継履歴の記録を止める
                runBlocking {
                    log.info("saveMessageChannel.close...")
                    saveMessageChannel.close()
                    log.info("saveMessageJob join...")
                    saveMessageJob.join()
                    log.info("saveMessageJob stopped.")
                }
                log.info("all resources are closed.")
            }
        }
    )
    while (true) {
        val limit = 86400_000L
        recordDir.sweepOldRecursive(limit)
        cacheDataDir.sweepOldRecursive(limit)
        cacheErrorDir.sweepOldRecursive(limit)
        sleep(3600_000L)
    }
}

fun File.sweepOldRecursive(limit: Long = 86400_000L): Int {
    log.info("(sweepOldRecursive $this")
    val expire = System.currentTimeMillis() - limit
    var remain = 0
    Files.walk(this.toPath()).use { pathStream ->
        pathStream.forEach { path: Path? ->
            val name = path?.name
            if (name == null || name == "" || name == "." || name == "..") return@forEach
            val file = path.toFile()
            ++remain
            fun delete() {
                file.delete()
                --remain
            }
            when {
                file.isDirectory -> {
                    when {
                        // F指定フォルダ自身は除外
                        file.canonicalPath == this.canonicalPath -> --remain
                        // サブフォルダを掃除してカラなら削除
                        file.sweepOldRecursive(limit) == 0 -> delete()
                    }
                }

                file.isFile -> when {
                    file.lastModified() > expire -> Unit
                    else -> delete()
                }
            }
        }
    }
    log.info(")sweepOldRecursive $this")
    return remain
}
