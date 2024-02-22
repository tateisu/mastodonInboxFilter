import io.ktor.client.HttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val log = LoggerFactory.getLogger("SpamCheck")

@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.digestSha256Base64() =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .let { Base64.encode(it) }

fun logStatus(
    prefix: String,
    badSign: String,
    status: APStatus,
    text: String,
) {
    log.info("$prefix $badSign ${status.sUrl} $text")
}

val reSpaces = """\s+""".toRegex()

suspend fun isSpam(
    status: APStatus,
    cacheDataDir: File,
    cacheErrorDir: File,
    httpClient: HttpClient,
    config: Config,
): Boolean {
    // 別の投稿への返信はチェックしない
    if (config.skipInReplyTo && !status.inReplyTo.isNullOrEmpty()) {
        return false
    }
    // メンション数が一定以上でないとチェックしない
    if (status.mentions?.let { it.size >= config.mentionMin } != true) {
        return false
    }

    // 添付データ複数はチェックしない
    if (status.attachments?.let { it.size > 1 } == true) {
        return false
    }

    // usernameの長さが範囲外ならチェックしない
    if (status.aUserName.length !in config.userNameLengthRange) {
        return false
    }

    // スキップ対象ドメインならチェックしない
    if (config.skipDomainSet.contains(status.aHost)) {
        return false
    }

    // ここのacctは不正確。misskeyだと id@host になるし、 mastodonだと@の後ろが vivaldi.net ではなく social.vivaldi.net になる
    // APベースだと正しいacctを取得するにはWebFingerの追加のリクエストが必要だが、実行コストを下げたい
    val acct = "${status.aUserName}@${status.aHost}"
    if (config.skipAcctSet.contains(acct)) {
        return false
    }

    // 本文のHTMLをテキストにする。改行やメンションは空白にする。
    val document = Jsoup.parse(status.content)
    for (br in document.getElementsByTag("br")) {
        br.replaceWith(TextNode(" "))
    }
    for (a in document.getElementsByTag("a")) {
        val clazz = a.attr("class") ?: ""
        when {
            clazz == "u-url mention" -> a.replaceWith(TextNode(" "))

            // 特に何もしない
            clazz.contains("hashtag") -> Unit

            // リンクは表示文字列を実URLに展開する
            else -> a.attr("href").takeIf { it.isNotEmpty() }
                ?.let { a.replaceWith(TextNode(" $it ")) }
        }
    }

    val text = document.text().trim().replace(reSpaces, " ")

    // 禁止ワードを含むか調べる
    val matched = config.badText?.filter { keyword -> text.contains(keyword) }
    if (!matched.isNullOrEmpty()) {
        logStatus("NG", "<word: ${matched.joinToString(", ")}>", status, text)
        return true
    }

    // 画像ダイジェストのマッチ
    val imageDigests = status.attachments?.filter {
        it.mediaType.startsWith("image/")
    }?.mapNotNull {
        try {
            // APの情報だと添付データのサイズは分からないので、ある程度までしか読まない
            val maxSize = 1_000_000
            val body = cachedGet(
                cacheDataDir = cacheDataDir,
                cacheErrorDir = cacheErrorDir,
                httpClient = httpClient,
                url = it.remoteUrl,
                sizeLimit = 1 + maxSize,
            )
            when {
                // maxSizeより大きいならダイジェストを取らない
                body.size > maxSize -> null
                else -> body.digestSha256Base64()
            }
        } catch (ex: Throwable) {
            val message = ex.message ?: ""
            if (message.contains("403 Forbidden")) {
                log.info("[$acct] ${it.remoteUrl} $message // maybe suspended at original site.")
            } else {
                log.info("[$acct] ${it.remoteUrl} $message")
            }
            null
        }
    }?.filter {
        !config.skipImageDigestSet.contains(it)
    }
    if (!imageDigests.isNullOrEmpty()) {
        val (bad, unknown) = imageDigests.partition { config.badImageDigestSet.contains(it) }
        if (bad.isNotEmpty()) {
            logStatus("NG", bad.first(), status, text)
            return true
        }
        for (digest in unknown) {
            logStatus("??", digest, status, text)
        }
    }

    return false
}
