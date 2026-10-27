package com.github.kr328.clash.core.bridge

/**
 * A failure that came out of the native (Go) core.
 *
 * The native bridge reports most failures through the `Throwable` message of a failed
 * `CompletableDeferred`, which the UI would otherwise surface as a bare
 * "Failed to load config" style string without any hint of what actually went wrong.
 * Wrapping the failure here keeps the original cause attached (so logs stay diagnosable)
 * while giving the UI a message that names the operation that failed.
 */
class ClashException(message: String, cause: Throwable? = null) : Exception(message, cause)
