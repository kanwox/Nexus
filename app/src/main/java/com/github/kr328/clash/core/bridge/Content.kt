package com.github.kr328.clash.core.bridge

import androidx.annotation.Keep
import androidx.core.net.toUri
import com.github.kr328.clash.common.Global
import java.io.FileNotFoundException

@Keep
object Content {
    @JvmStatic
    fun open(url: String): Int {
        val uri = url.toUri()

        if (uri.scheme != "content") {
            throw UnsupportedOperationException("Unsupported scheme ${uri.scheme}")
        }

        val pfd = Global.application.contentResolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("$uri not found")

        // The fd itself is handed over to the native side, which owns it from then on.
        // detachFd() releases the descriptor from the ParcelFileDescriptor, so the wrapper
        // no longer refers to a valid fd; closing the wrapper afterwards is therefore safe
        // (it only releases the wrapper's own state) and it prevents the finalizer from
        // closing a descriptor the native side already owns.
        return try {
            pfd.detachFd()
        } finally {
            runCatching { pfd.close() }
        }
    }
}
