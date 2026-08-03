package com.buddystudy.backend.common.application.stream

/** Signals that the handler already released its Inbox lease for retry. */
class StreamRetryScheduledException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
