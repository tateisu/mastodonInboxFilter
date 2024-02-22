package util

inline fun <reified T : Any> Any?.cast() = this as? T
inline fun <reified T : Any> Any.castNotNull() = this as T

fun ByteArray.notEmpty() = if (this.isEmpty()) null else this

fun <T : List<*>> T?.notEmpty() = if (isNullOrEmpty()) null else this

fun <T:CharSequence> T?.notEmpty() = if(isNullOrEmpty()) null else this
fun <T:CharSequence> T?.notBlank() = if(isNullOrEmpty() || isBlank()) null else this
