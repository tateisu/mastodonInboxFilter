@file:Suppress("LoggingStringTemplateAsArgument")

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import util.JsonObject
import util.decodeJsonObject
import util.jsonObject
import util.notEmpty
import util.safeFileName
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.time.ZoneId
import java.time.ZonedDateTime

private val log = LoggerFactory.getLogger("AutoReport")

fun String.wildcardToRegex(): Regex {
    val rePart = """([?.]|\*+|[^?.*]+)""".toRegex()
    val escaped = rePart.replace(this) { mr ->
        val part = mr.groupValues[1]
        when {
            part == "." -> """\."""
            part == "?" -> "."
            part.startsWith("*") -> ".*"
            else -> Regex.escape(part)
        }
    }
    return """\A$escaped\z""".toRegex(RegexOption.DOT_MATCHES_ALL)
}

/**
 * HTTPリクエストを投げて404だったらfallbackを行う
 */
inline fun <R> catch404(
    block: () -> R,
    fallback: () -> R,
): R {
    try {
        return block()
    } catch (ex: Throwable) {
        if ((ex as? ClientRequestException)?.response?.status ==
            HttpStatusCode.NotFound
        ) {
            return fallback()
        }
        throw ex
    }
}

// インスタンス情報の取得
suspend fun getInstanceInfo(
    httpClient: HttpClient,
    host: String,
    timeoutMs: Long? = null,
): JsonObject {
    val hostFixed = when {
        host.startsWith("https://") -> host
        else -> "https://$host"
    }
    var url = ""
    try {
        return catch404(
            block = {
                url = "$hostFixed/api/v2/instance"
                httpClient.get(url) {
                    if (timeoutMs != null) {
                        timeout {
                            requestTimeoutMillis = timeoutMs
                            socketTimeoutMillis = timeoutMs
                            connectTimeoutMillis = timeoutMs
                        }
                    }
                }
            },
            fallback = {
                url = "$hostFixed/api/v1/instance"
                httpClient.get(url) {
                    if (timeoutMs != null) {
                        timeout {
                            requestTimeoutMillis = timeoutMs
                            socketTimeoutMillis = timeoutMs
                            connectTimeoutMillis = timeoutMs
                        }
                    }
                }
            }
        ).body<String>().decodeJsonObject()
    } catch (ex: Throwable) {
        val message = ex.message
            ?.replace("""Text: "<!DOCTYPE.*""".toRegex(RegexOption.DOT_MATCHES_ALL), "")
            ?.replace("""\s*Text: ""\s*""".toRegex(), "")
            ?.replace(url, " ")
            ?: ""

        error("${ex.javaClass.simpleName} $message")
    }
}

fun createCheckFile(folder: File, url: String) {
    File(folder, url.safeFileName()).writeText(url)
}

// apiHost に認証付きでリクエストを投げる
suspend fun jsonApi(
    httpClient: HttpClient,
    config: Config.AutoReport,
    method: HttpMethod,
    path: String,
    params: JsonObject?,
) {
    val apiHost = config.apiHost
    val accessToken = config.accessToken
    httpClient.request {
        this.method = method
        url("$apiHost$path")
        header("Authorization", "Bearer $accessToken")
        if (params != null && method == HttpMethod.Post) {
            header("Content-Type", "application/json")
            setBody(params.toString().encodeToByteArray())
        }
    }
    // throw error if failed.
}

suspend fun post(
    httpClient: HttpClient,
    config: Config.AutoReport,
    status: String,
    visibility: String,
) {
    jsonApi(
        httpClient,
        config,
        HttpMethod.Post,
        "/api/v1/statuses",
        params = jsonObject {
            put("status", status)
            put("visibility", visibility)
        }
    )
}

//////////////////////////
// SPAM URL がまだ有効か調べる
suspend fun checkSpamUrls(
    host: String,
    httpClient: HttpClient,
    checkDir: File,
    maxOutput: Int,
    urls: Collection<String>,
) = buildList {
    for (url in urls) {
        if (size > maxOutput) break
        val checkFile = File(checkDir, url.safeFileName())
        if (checkFile.exists()) {
            log.debug("skip: $url")
            continue
        }
        try {
            httpClient.head(url) {
            }
            add(url)
        } catch (ex: Throwable) {
            val message = ex.message
                ?.replace("""Text: "<!DOCTYPE.*""".toRegex(RegexOption.DOT_MATCHES_ALL), "")
                ?.replace(url, " ")
                ?: ""
            val className = ex.javaClass.simpleName
            val short = "$className $message"
            when {
                short.contains("403 Forbidden") ||
                        short.contains("410 Gone")
                -> {
                    log.warn("[$host] post deleted? $short")
                    createCheckFile(checkDir, url)
                }

                short.contains("HttpRequestTimeoutException") ||
                        short.contains("TLSException") ||
                        short.contains("Invalid TLS record type") ||
                        short.contains("502 Bad Gateway") ||
                        message.contains("Connection timed out") ||
                        message.contains("No route to host") ||
                        message.contains("SSL connect attempt failed")
                -> {
                    log.warn("[$host] server closed? $short")
                    urls.forEach { createCheckFile(checkDir, it) }
                    break
                }

                """5\d{2} """.toRegex().containsMatchIn(short) -> {
                    error("server temporary error. $short")
                }

                else -> error(short)
            }
        }
    }
}

//////////////////////////
// ホスト別にSPAMを報告する

fun messageText(
    mentionTo: String,
    urls: List<String>,
    maxChars: Int,
    urlChars: Int,
): String = buildString {
    append("$mentionTo automated message: your server send SPAM.\n please suspend SPAM accounts and consider to block mail address domain.\n some samples of posts:")
    var chars = length
    for (url in urls) {
        val urlLength = if (url.length > urlChars) url.length else urlChars
        if (maxChars - chars >= 3 + urlLength) {
            append(" $url")
            chars += 1 + urlLength
        } else {
            append(" …")
            break
        }
    }
    log.info("[$mentionTo] chars=$chars, length=$length")
}

val reTime = """\A(\d+)-(\d+)-(\d+)T(\d+):(\d+):(\d+) INFO\s+SpamCheck - NG """.toRegex()
val rePostUrl = """(https://([^/]+)/@[^/]+/\d+)""".toRegex()

// ログファイルのcanonicalPathの集合を返す
fun listLogFiles(
    config: Config.AutoReport,
    expire: Long,
): Collection<String> {
    val logFilePaths = HashSet<String>()
    config.logFilePrimary.notEmpty()?.let { logFilePaths.add(File(it).canonicalPath) }
    config.logFileSecondaryFolder?.notEmpty()?.let { folderPath ->
        val reName = config.logFileSecondaryNamePattern?.wildcardToRegex()
            ?: return@let
        val folder = File(folderPath)
        for (name in folder.list() ?: emptyArray()) {
            if (!reName.matches(name)) continue
            val file = File(folder, name)
            if (!file.isFile) continue
            if (file.lastModified() < expire) continue

            logFilePaths.add(file.canonicalPath)
        }
    }
    return logFilePaths
}

fun readLogFile(
    dst: HashMap<String, HashMap<String, Long>>,
    path: String,
    expire: Long,
) {
    val logFile = File(path)
    if (!logFile.isFile) return
    if (logFile.lastModified() < expire) return
    log.info("reading $logFile")
    BufferedReader(InputStreamReader(FileInputStream(logFile), Charsets.UTF_8)).use { reader ->
        while (true) {
            var line = reader.readLine() ?: break
            var matchResult: MatchResult? = null
            line = reTime.replace(line) {
                matchResult = it
                ""
            }
            val gvTime = matchResult?.groupValues ?: continue
            val t = ZonedDateTime.of(
                gvTime[1].toInt(),
                gvTime[2].toInt(),
                gvTime[3].toInt(),
                gvTime[4].toInt(),
                gvTime[5].toInt(),
                gvTime[6].toInt(),
                0, // nano
                ZoneId.systemDefault()
            ).toInstant().toEpochMilli()
            if (t < expire) continue
            val gvUrl = rePostUrl.find(line)?.groupValues ?: continue
            val url = gvUrl[1]
            val host = gvUrl[2]
            dst.getOrPut(host) { HashMap() }[url] = t
        }
    }
}

suspend fun findAdminMention(
    httpClient: HttpClient,
    adminMentions: HashMap<String, String>,
    adminErrors: HashMap<String, String>,
    skipHosts: Set<String>,
    host: String,
) {
    if (skipHosts.contains(host)) {
        adminErrors[host] = "(skipped.)"
        return
    }
    val info = try {
        getInstanceInfo(httpClient, host, timeoutMs = 30_000L)
    } catch (ex: Throwable) {
        adminErrors[host] = ex.message ?: ex.javaClass.simpleName
        return
    }
    val apDomain = info.string("domain")
    val userName = info.jsonObject("contact")?.jsonObject("account")?.string("username")
    if (apDomain == null || userName == null) {
        adminErrors[host] = "(can't find admin account.)"
        return
    }
    adminMentions[host] = "@$userName@$apDomain"
}

fun logHostSummary(
    host: String,
    count: Int,
    error: String?,
    mention: String?,
) {
    when {
        error != null -> log.info("$count $host $error")
        mention != null -> log.info("$count $host $mention")
        else -> log.info("$count $host ??")
    }
}

suspend fun testReport(
    httpClient: HttpClient,
    config: Config.AutoReport,
    checkDir: File,
    maxChars: Int,
    urlChars: Int,
    mentionTo: String,
) {
    val activeUrls = checkSpamUrls(
        host = "test",
        httpClient = httpClient,
        checkDir = checkDir,
        maxOutput = 6,
        // SPAM投稿のURLを日時降順でソート
        urls = buildList {
            repeat(10) {
                add("https://juggler.jp/$it")
            }
        },
    )
    if (activeUrls.isEmpty()) {
        log.info("[test] activeUrls is empty.")
    } else {
        val text = messageText(
            maxChars = maxChars,
            urlChars = urlChars,
            mentionTo = mentionTo,
            urls = activeUrls,
        )
        post(
            httpClient = httpClient,
            config = config,
            visibility = "direct",
            status = text,
        )
        activeUrls.forEach {
            createCheckFile(checkDir, it)
        }
    }
}

suspend fun report(
    config: Config.AutoReport,
    httpClient: HttpClient,
    mentionTo: String?,
    checkDir: File,
    noPost: Boolean,
    maxChars: Int,
    urlChars: Int,
    entry: Map.Entry<String, HashMap<String, Long>>,
) {
    mentionTo ?: return
    val host = entry.key

    val activeUrls = try {
        checkSpamUrls(
            host = host,
            httpClient = httpClient,
            checkDir = checkDir,
            maxOutput = 6,
            urls = entry.value.entries.sortedByDescending { it.value }.map { it.key },
        )
    } catch (ex: Throwable) {
        log.error("[$host] checkSpamUrls failed.", ex)
        return
    }
    if (noPost || activeUrls.isEmpty()) return
    try {
        val text = messageText(
            maxChars = maxChars,
            urlChars = urlChars,
            mentionTo = mentionTo,
            urls = activeUrls,
        )
        post(
            httpClient = httpClient,
            config = config,
            visibility = "direct",
            status = text,
        )
        activeUrls.forEach {
            createCheckFile(checkDir, it)
        }
    } catch (ex: Throwable) {
        log.error("[$host] report failed.", ex)
    }
}

suspend fun autoReport(
    config: Config.AutoReport,
    noPost: Boolean,
    hours: Int,
    testMentionTo: String?,
) {
    HttpClient(CIO) {
        expectSuccess = true
        install(UserAgent) {
            agent = config.userAgent
        }
        install(HttpTimeout) {
            socketTimeoutMillis = config.requestTimeoutMs
            requestTimeoutMillis = config.requestTimeoutMs
            connectTimeoutMillis = config.requestTimeoutMs
        }
    }.use { httpClient ->
        // 処理済みURLを保持するフォルダ
        val checkDir = File(config.autoReportDir)
        checkDir.mkdirs()

        // 報告を投稿するサーバの情報を読んで、投稿の最大文字数などの情報を取得する
        val myServerInfo = getInstanceInfo(httpClient, config.apiHost)
        val maxChars = myServerInfo.jsonObject("configuration")
            ?.jsonObject("statuses")
            ?.int("max_characters")
            ?: error("missing maxChars")
        val urlChars = myServerInfo.jsonObject("configuration")
            ?.jsonObject("statuses")
            ?.int("characters_reserved_per_url")
            ?: error("missing urlChars")
        log.info("maxChars=$maxChars, urlChars=$urlChars")

        if (!testMentionTo.isNullOrEmpty()) {
            testReport(
                httpClient = httpClient,
                config = config,
                checkDir = checkDir,
                maxChars = maxChars,
                urlChars = urlChars,
                mentionTo = testMentionTo,
            )
            return@use
        }

        //////////////////////////////
        // ログファイルを読んでホスト別に報告する

        val expire = System.currentTimeMillis() - 3600_000L * hours
        val hosts = HashMap<String, HashMap<String, Long>>()

        for (logFilePath in listLogFiles(config, expire)) {
            readLogFile(
                dst = hosts,
                path = logFilePath,
                expire = expire,
            )
        }
        // SPAM件数降順でソート
        val hostsSorted = hosts.entries.sortedByDescending { it.value.size }

        // 管理者アカウントを探す
        val adminMentions = HashMap<String, String>()
        val adminErrors = HashMap<String, String>()
        val skipHosts = config.skipHost?.toSet() ?: emptySet()

        log.info("read instance info...")
        supervisorScope {
            hostsSorted.map { entry ->
                async {
                    findAdminMention(
                        httpClient = httpClient,
                        adminMentions = adminMentions,
                        adminErrors = adminErrors,
                        skipHosts = skipHosts,
                        host = entry.key,
                    )
                }
            }.awaitAll()
        }
        supervisorScope {
            hostsSorted.map { entry ->
                async {
                    val host = entry.key
                    if (skipHosts.contains(host)) return@async
                    report(
                        config = config,
                        httpClient = httpClient,
                        mentionTo = adminMentions[entry.key],
                        checkDir = checkDir,
                        noPost = noPost,
                        maxChars = maxChars,
                        urlChars = urlChars,
                        entry = entry
                    )
                }
            }.awaitAll()
        }

        // 件数降順でホスト別サマリを表示
        for (entry in hostsSorted) {
            val host = entry.key
            logHostSummary(
                host = host,
                count = entry.value.size,
                error = adminErrors[host],
                mention = adminMentions[host],
            )
        }
    }
}
