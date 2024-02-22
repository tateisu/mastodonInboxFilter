package util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object EmptyScope :CoroutineScope{
    override val coroutineContext: CoroutineContext
        get() = EmptyCoroutineContext + Dispatchers.Default
}
