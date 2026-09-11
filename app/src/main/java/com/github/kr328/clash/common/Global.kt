package com.github.kr328.clash.common

import android.app.Application
import androidx.annotation.Keep

@Keep
object Global {
    val application: Application
        get() = application_

    private lateinit var application_: Application

    fun init(application: Application) {
        this.application_ = application
    }
}
