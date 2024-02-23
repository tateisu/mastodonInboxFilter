import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File

@Serializable
class Config(
    // HTTPサーバの待機ポート
    val listenPort: Int = 8901,
    // HTTPサーバの待機アドレス
    val listenHost: String = "0.0.0.0",
    // PIDファイル
    val pidFile: String = "mastodonInboxFilter.pid",

    // リダイレクト先のMastodon の /inbox URL
    // 設定必須
    val redirectUrl: String = "http://0.0.0.0",

    // 記録フォルダ
    val recordDir: String = "record",
    val cacheDir: String = "cache",

    // HTTPクライアントのタイムアウト
    val requestTimeoutMs: Long,
    // HTTPクライアントのユーザエージェント
    val userAgent: String,

    // メンション数の下限
    val mentionMin: Int,

    // screen_name の下限
    val userNameLengthMin: Int,
    // screen_name の上限
    val userNameLengthMax: Int,

    val badImageDigest: List<String>? = null,
    val badText: List<String>? = null,

    val skipImageDigest: List<String>? = null,
    val skipAcct: List<String>? = null,
    val skipDomain: List<String>? = null,

    val skipInReplyTo: Boolean = true,

    val autoReport: AutoReport? = null,
) {
    @Transient
    var configFile : File = File("(configFile prop was not set)")

    @Transient
    val skipDomainSet = skipDomain?.toSet() ?: emptySet()

    @Transient
    val skipAcctSet = skipAcct?.toSet() ?: emptySet()

    @Transient
    val badImageDigestSet = badImageDigest?.toSet() ?: emptySet()

    @Transient
    val skipImageDigestSet = skipImageDigest?.toSet() ?: emptySet()

    @Transient
    val userNameLengthRange = userNameLengthMin..userNameLengthMax

    /**
     * 検出結果の自動報告
     */
    @Serializable
    class AutoReport(
        // reporter mastodon account: API host such as https://mastodon.social
        val apiHost: String,
        // reporter mastodon account: API access token
        val accessToken: String,

        // HTTPクライアントのユーザエージェント
        val userAgent: String,
        // HTTPクライアントのタイムアウト
        val requestTimeoutMs: Long,

        // folder to save reported urls
        val autoReportDir:String = "autoReport",

        val logFilePrimary: String = "./mastodonInboxFilter.log",
        val logFileSecondaryFolder: String? = ".",
        val logFileSecondaryNamePattern: String? = "mastodonInboxFilter.log.*",

        val skipHost: List<String>? = null,
    )
}
