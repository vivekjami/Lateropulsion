package com.lateropulsion.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Injectable dispatcher set so that real-time and IO work never touch the main thread by accident. */
public interface LpDispatchers {
    public val io: CoroutineDispatcher
    public val default: CoroutineDispatcher
    public val main: CoroutineDispatcher
}

public class DefaultDispatchers(override val main: CoroutineDispatcher = Dispatchers.Default) : LpDispatchers {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}

public class SingleDispatchers(dispatcher: CoroutineDispatcher) : LpDispatchers {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}
