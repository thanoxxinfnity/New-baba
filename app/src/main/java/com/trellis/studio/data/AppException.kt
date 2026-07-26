package com.trellis.studio.data

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
    is IOException ->
        "A network error occurred. Please try again."
    else -> message ?: "Something went wrong. Please try again."
}
