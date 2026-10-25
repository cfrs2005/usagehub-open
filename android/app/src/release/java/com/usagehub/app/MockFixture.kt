package com.usagehub.app

import android.content.Context

/** Release builds carry no fixture: the mock path is unreachable and the debug data is not even packaged. */
object MockFixture {
    @Suppress("UNUSED_PARAMETER")
    fun source(context: Context): Source? = null
}
