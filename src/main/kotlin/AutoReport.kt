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
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("AutoReport")

/**
 * HTTPリクエストを投げて404だったら別のリクエストにfallbackする
 */
private suspend fun <R> fallback404(blocks: List<suspend () -> R>): R {
    val last = blocks.indices.last
    for (i in blocks.indices) {
        try {
            return blocks[i]()
        } catch (ex: Throwable) {
            val is404 = (ex as? ClientRequestException)?.response?.status == HttpStatusCode.NotFound
            when {
                is404 && i < last -> continue
                else -> throw ex
            }
        }
    }
    error("WILL_NOT_HAPPEN")
}

/**
 * あるURLが処理済みかどうか
 */
private fun existsCheckFile(folder: File, url: String) =
    File(folder, url.safeFileName()).exists()

/**
 * あるURLが処理済みであることを保存する
 */
private fun createCheckFile(folder: File, url: String) {
    File(folder, url.safeFileName()).writeText(url)
}

/////////////////////////////////////////////////////
// instance information

/**
 * Mastodonのインスタンス情報の取得
 */
private suspend fun getInstanceInfo(
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
        return fallback404(
            blocks = arrayOf(
                "$hostFixed/api/v2/instance",
                "$hostFixed/api/v1/instance"
            ).map {
                {
                    url = it
                    httpClient.get(it) {
                        if (timeoutMs != null) {
                            timeout {
                                requestTimeoutMillis = timeoutMs
                                socketTimeoutMillis = timeoutMs
                                connectTimeoutMillis = timeoutMs
                            }
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

/**
 * 指定ホストからインスタンス情報を読んでサーバ管理者のメンション先を取得する
 * @param adminMentions (output) 取得したメンション先が記録される
 * @param adminErrors (output) 発生したエラーが記録される
 * @param httpClient KtorのHttpClient
 * @param skipHosts ホストがスキップ対象か調べる
 * @param host 調査対象のホスト名
 */
private suspend fun findAdminMention(
    adminMentions: MutableMap<String, String>,
    adminErrors: MutableMap<String, String>,
    httpClient: HttpClient,
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
    if (apDomain == null) {
        adminErrors[host] = "(can't find AP domain.)"
        return
    }
    val userName = info.jsonObject("contact")?.jsonObject("account")?.string("username")
    if (userName == null) {
        adminErrors[host] = "(can't find admin account.)"
        return
    }
    adminMentions[host] = "@$userName@$apDomain"
}

/////////////////////////////////////////////////////

/**
 * 認証付きでリクエストを投げる
 * @param httpClient KtorのHttpClient
 * @param config 送信先と認証トークンを含む設定情報
 * @param method APIのHTTP Method
 * @param path APIのpath /で始まる
 * @param params APIにわたすパラメータ
 */
private suspend fun jsonApi(
    httpClient: HttpClient,
    config: Config.AutoReport,
    method: HttpMethod,
    path: String,
    params: JsonObject? = null,
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

/**
 * Mastodonの投稿APIを呼び出す
 * @param httpClient KtorのHttpClient
 * @param config 送信先と認証トークンを含む設定情報
 * @param status 投稿の本文
 * @parma visibility 投稿の可視性
 */
suspend fun postStatus(
    httpClient: HttpClient,
    config: Config.AutoReport,
    status: String,
    visibility: String,
) = jsonApi(
    httpClient,
    config,
    HttpMethod.Post,
    "/api/v1/statuses",
    params = jsonObject {
        put("status", status)
        put("visibility", visibility)
    }
)

/**
 * 報告の投稿の本文を作成する
 * @return 投稿の本文
 * @param prefix 本文の先頭に付与するテキスト
 * @param urls SPAMのURLのリスト
 * @param maxChars 投稿可能な最大文字数
 * @param urlChars URLの文字数の上限。URLがこれより長い場合、文字数判定においては上限クリップされる
 */
fun messageText(
    prefix: String,
    urls: List<String>,
    maxChars: Int,
    urlChars: Int,
): String = buildString {
    append("$prefix automated message: your server send SPAM.\n please suspend SPAM accounts and consider to block mail address domain.\n some samples of posts:")
    val more = " (more)"
    var chars = length
    for (url in urls) {
        val urlLength = if (url.length > urlChars) url.length else urlChars
        if (maxChars - chars >= urlLength + more.length + 1) {
            append(" $url")
            chars += 1 + urlLength
        } else {
            append(more)
            break
        }
    }
    log.info("[$prefix] chars=$chars, length=$length")
}

/////////////////////////////////////////

/**
 * SPAM URL がまだ有効か調べる
 * @return 有効で未報告のURLのリスト
 * @param logPrefix ログ出力のprefix。対象ホストなどを想定
 * @param httpClient KtorのHttpClient
 * @param checkDir URLが処理済みかどうか調べるためのフォルダ
 * @param maxOutput 最大出力数
 * @param urls 調査対象のURLの集合
 */
suspend fun checkSpamUrls(
    logPrefix: String,
    httpClient: HttpClient,
    checkDir: File,
    maxOutput: Int,
    urls: Iterable<String>,
) = buildList {
    for (url in urls) {
        if (size > maxOutput) break
        if (existsCheckFile(checkDir, url)) {
            log.debug("skip: $url")
            continue
        }
        try {
            httpClient.head(url, block = {})
            add(url)
        } catch (ex: Throwable) {
            val message = ex.message
                ?.replace("""Text: "<!DOCTYPE.*""".toRegex(RegexOption.DOT_MATCHES_ALL), "")
                ?.replace(url, " ")
                ?: ""
            val short = "${ex.javaClass.simpleName} $message"
            when {
                short.contains("403 Forbidden") ||
                        short.contains("410 Gone")
                -> {
                    log.warn("[$logPrefix] post deleted? $short")
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
                    log.warn("[$logPrefix] server closed? $short")
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

//////////////////////////////////////////////////////////
// ログファイルの読み込み

private val reLogHeader = """\A(\d+)-(\d+)-(\d+)T(\d+):(\d+):(\d+) INFO\s+SpamCheck - NG """.toRegex()
private val rePostUrl = """(https://([^/]+)/@[^/]+/\d+)""".toRegex()

/**
 * ログファイルを読んでSPAMのURLと時刻を収集する
 * @param dst (output)ホスト別のURLと時刻のマップ
 * @param logFile ログファイル
 * @param expire SPAM URL の収集対象の時刻の下限
 */
private fun readLogFile(
    dst: MutableMap<String, MutableMap<String, Long>>,
    logFile: File,
    expire: Long,
) {
    if (!logFile.isFile) return
    if (logFile.lastModified() < expire) return
    log.info("reading $logFile")
    BufferedReader(InputStreamReader(FileInputStream(logFile), Charsets.UTF_8)).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            // 行内のヘッダ部分を読む
            val mrHeader = reLogHeader.find(line) ?: continue
            val gvTime = mrHeader.groupValues
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

            // SPAM投稿URLを読む
            val gvUrl = rePostUrl.find(line, startIndex = mrHeader.range.last + 1)
                ?.groupValues ?: continue
            val url = gvUrl[1]
            val host = gvUrl[2]
            // dst に記録する
            dst.getOrPut(host) { ConcurrentHashMap() }[url] = t
        }
    }
}

/**
 * ワイルドカード(`*`,`?`) を正規表現に変換する
 * - 単体テストがあるのでpublic
 */
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
 * ログファイルを探す
 * @param config ログファイルの指定のある設定情報
 * @param expire これより古いファイルは対象外
 * @param block 見つかったログファイルが渡されるラムダ式
 */
fun forEachLogFile(
    config: Config.AutoReport,
    expire: Long,
    block: (File) -> Unit,
) {
    val duplicates = HashSet<String>()
    fun addLogFile(file: File) {
        if (!file.isFile) return
        if (file.lastModified() < expire) return
        val path = file.canonicalPath
        if (duplicates.contains(path)) return
        duplicates.add(path)
        block(file)
    }

    config.logFilePrimary.notEmpty()?.let { addLogFile(File(it)) }
    val reName = config.logFileSecondaryNamePattern?.wildcardToRegex()
        ?: return
    val folder = config.logFileSecondaryFolder?.notEmpty()?.let { File(it) }
    if (folder?.isDirectory == true) {
        for (name in folder.list() ?: emptyArray()) {
            if (!reName.matches(name)) continue
            addLogFile(File(folder, name))
        }
    }
}

/////////////////////////////////////////////////////////////

/**
 * reportに使う各メソッドの試験
 */
suspend fun testReport(
    httpClient: HttpClient,
    config: Config.AutoReport,
    checkDir: File,
    maxChars: Int,
    urlChars: Int,
    mentionTo: String,
) {
    val activeUrls = checkSpamUrls(
        logPrefix = "test",
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
            prefix = mentionTo,
            urls = activeUrls,
        )
        postStatus(
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

/**
 * ホスト別に以下の処理を行う
 * - checkSpamUrls
 * - messageText
 * - postStatus
 * - createCheckFile
 */
suspend fun report(
    config: Config.AutoReport,
    httpClient: HttpClient,
    mentionTo: String?,
    checkDir: File,
    noPost: Boolean,
    maxChars: Int,
    urlChars: Int,
    entry: Map.Entry<String, Map<String, Long>>,
) {
    mentionTo ?: return
    val host = entry.key

    val activeUrls = try {
        checkSpamUrls(
            logPrefix = host,
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
            prefix = mentionTo,
            urls = activeUrls,
        )
        postStatus(
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

/**
 * ホスト別にSPAM投稿数、エラー状態、メンション先を表示する
 */
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

/**
 * 自動報告
 * - ログファイルをスキャンして指定時間数以内のSPAM投稿URLを列挙する
 * - ホスト名別に報告先の管理者アカウントを取得する
 * - SPAM投稿URLがまだ有効か(削除されてないか)調べる
 * - 有効なSPAM投稿が残っていればDMを投げて報告する
 *
 * @param config 設定情報
 * @param noPost 報告投稿を行わないなら真。 --noPost
 * @param hours 指定した時間数まで遡って投稿URLを列挙する。 --hours Int
 * @param testMentionTo 報告機能の試験のメンション先。 --testRepost String
 */
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
        val hosts: MutableMap<String, MutableMap<String, Long>> = ConcurrentHashMap()

        forEachLogFile(config, expire) { file ->
            readLogFile(
                dst = hosts,
                logFile = file,
                expire = expire,
            )
        }
        if (hosts.isEmpty()) {
            log.info("spam is not found.")
            return@use
        }

        // SPAM件数降順でソート
        val hostsSorted = hosts.entries.sortedByDescending { it.value.size }

        // 管理者アカウントを探す
        log.info("read instance info…")
        val adminMentions: MutableMap<String, String> = ConcurrentHashMap()
        val adminErrors: MutableMap<String, String> = ConcurrentHashMap()
        val skipHosts = config.skipHost?.toSet() ?: emptySet()
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

        // 報告処理
        log.info("check & make report…")
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
        log.info("spams within $hours hours.")
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
