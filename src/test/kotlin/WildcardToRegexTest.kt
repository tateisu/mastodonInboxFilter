
import io.github.xn32.json5k.Json5
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.http.encodeURLQueryComponent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.slf4j.LoggerFactory
import util.decodeJsonObject
import java.io.File
import kotlin.test.DefaultAsserter.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WildcardToRegexTest {


    @Test
    fun wildcardToRegexTest(){
        fun t(src:String,expected:String){
            val actual = src.wildcardToRegex().toString()
            assertEquals(
                expected=expected,
                actual = actual,
                "src=$src",
            )
        }
        t("abc.def","""\A\Qabc\E\.\Qdef\E\z""")
        t("*.*", """\A.*\..*\z""")
        t("?.?", """\A.\..\z""")
    }
}
