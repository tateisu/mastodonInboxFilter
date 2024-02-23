package util

private val reBadChars = """[\x00-\x20?*:<>\\\/"|]""".toRegex()

fun String.safeFileName():String = reBadChars.replace(this, "-")
