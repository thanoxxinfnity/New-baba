package com.trellis.studio.data

import android.os.NetworkOnMainThreadException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed class AppException(message: String) : Exception(message) {
    class Network(message: String) : AppException(message)
    class Api(message: String) : AppException(message)
}

/** Maps low-level failures to a short, user-facing message. */
fun Throwable.toUserMessage(): String = when (this) {
    is AppException -> message ?: "Something went wrong."
    is SocketTimeoutException, is InterruptedIOException ->
        "The request timed out. Check your connection and try again."
    is UnknownHostException ->
        "No internet connection. Check your network and try again."
    is NetworkOnMainThreadException ->
        "Internal error (network call on main thread). Please report this."
    is IOException ->
        "A network error occurred. Please try again."
    else -> "Something went wrong (${this::class.simpleName ?: "unknown error"}). Please try again."
}
