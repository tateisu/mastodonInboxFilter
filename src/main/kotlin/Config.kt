import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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

    val mentionMin: Int,

    // HTTPクライアントのタイムアウト
    val requestTimeoutMs: Long,
    // HTTPクライアントのユーザエージェント
    val userAgent: String,

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
) {
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
}
