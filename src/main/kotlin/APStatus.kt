import org.slf4j.LoggerFactory
import util.JsonObject
import util.notBlank

class APStatus(
    // host of user account (apiHost, not apDomain)
    val aHost: String,
    // user name
    val aUserName: String,
    // content HTML
    val content: String,
    // status URL
    val sUrl: String,

    val mentions: List<Mention>?,

    val attachments: List<Attachment>?,

    val inReplyTo:String?,
) {
    class Mention(
        val name: String,
        val href: String,
    )

    class Attachment(
        val mediaType: String,
        val remoteUrl: String,
    )
}

private val log = LoggerFactory.getLogger("APStatus")

// https://mastodon.social/users/tateisu
// https://misskey.io/users/9iwbo5u8ow
// https://fediverse.blog/@/mukkizignu/
private val reMastodonActor = """\Ahttps://([^/]+)/(?:users?|@)/([^/]+)/?\z""".toRegex()

/**
 * ActivityPubのCreate Note を雑にパースする
 * Create Note ではない場合はnullを返す
 * - ほか必要なものがなさそうなら例外を出す
 */
fun JsonObject.toAPStatus(
    debugPrefix: String,
): APStatus? {
    val root = this
    when (val type = root.string("type")) {
        "Create" -> Unit
        else -> {
            log.debug("$debugPrefix root.type is $type. id=${root.string("id")}")
            return null
        }
    }

    val obj = root.jsonObject("object") ?: error("missing object. $this")
    if (obj.string("type") != "Note") {
        log.debug("$debugPrefix obj.type is not Note. ${obj.string("type")}")
        return null
    }

    // アカウントのusername とホスト名を適当に調べる。
    //
    val actor = root.string("actor") ?: error("missing actor. $this")
    val actorMatch = reMastodonActor.find(actor)
    if (actorMatch == null) {
        log.info("actor not match. $actor")
        return null
    }

    val mentions = obj.jsonArray("tag")?.objectList()?.mapNotNull {
        val href = it.string("href")?.notBlank()
        val name = it.string("name")?.notBlank()
        when {
            href == null || name == null -> null
            it.string("type") != "Mention" -> null
            else -> APStatus.Mention(
                href = href,
                name = name,
            )
        }
    }
    val attachments = obj.jsonArray("attachment")?.objectList()?.mapNotNull {
        val mediaType = it.string("mediaType").notBlank()
        val url = it.string("url").notBlank()
        when {
            it.string("type") != "Document" -> null
            mediaType == null || url == null -> null
            else -> APStatus.Attachment(
                mediaType = mediaType,
                remoteUrl = url,
            )
        }
    }

    return APStatus(
        aHost = actorMatch.groupValues[1],
        aUserName = actorMatch.groupValues[2],
        content = obj.string("content") ?: error("missing content. $this"),
        sUrl = obj.string("url") // mastodon
            ?: obj.string("id") // https://misskey.io/notes/9q05g8osu733039l
            ?: error("missing status url. $this"),
        mentions = mentions,
        attachments = attachments,
        inReplyTo = obj.string("inReplyTo")
    )
}

