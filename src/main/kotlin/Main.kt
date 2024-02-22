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

private val log = LoggerFactory.getLogger("Main")

fun main(args: Array<String>) {
    System.`in`.close()

    val myPid = ProcessHandle.current().pid()

    val parser = ArgParser("java -jar mastodonInboxFilter.jar")
    val configPath by parser.argument(ArgType.String, description = "config file").optional().default("config.json5")
    val configTest by parser.option(ArgType.Boolean, fullName = "configTest", description = "config test only")
    parser.parse(args)

    val config: Config = Json5.decodeFromString(File(configPath).readText())

    if (configTest == true) return

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
        sweepFolders(recordDir, cacheDataDir, cacheErrorDir)
        sleep(3600_000L)
    }
}

fun sweepFolders(vararg folders: File, limit: Long = 86400_000L) {
    for (folder in folders) {
        val names = try {
            folder.list() ?: continue
        } catch (ex: Throwable) {
            log.error("listFiled failed. $folder", ex)
            continue
        }
        val expire = System.currentTimeMillis() - limit
        for (name in names) {
            val file = File(folder, name)
            when {
                !file.isFile -> Unit
                file.lastModified() > expire -> Unit
                else -> file.delete()
            }
        }
    }
}
