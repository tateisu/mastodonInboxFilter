import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import kotlin.jvm.optionals.getOrNull
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("PidFileWriter")

fun writePidFile(
    pidFile: File,
    myPid: Long,
) {
    try {
        val pid = pidFile.readText().toLong()
        val proc = ProcessHandle.of(pid).getOrNull()
        if (proc != null && pid != myPid) {
            log.error("old process $pid is alive. please kill old...")
            exitProcess(1)
        }
    } catch (ex: Throwable) {
        when (ex) {
            is FileNotFoundException -> Unit
            else -> throw IllegalStateException(
                "can't read PID from ${pidFile.canonicalPath}",
                ex
            )
        }
    }
    try {
        pidFile.writeText(myPid.toString())
    } catch (ex: Throwable) {
        throw IllegalStateException(
            "can't write PID to ${pidFile.canonicalPath}",
            ex
        )
    }
}
