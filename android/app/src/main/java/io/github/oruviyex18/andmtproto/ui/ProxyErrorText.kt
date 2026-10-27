package io.github.oruviyex18.andmtproto.ui

import android.content.Context
import io.github.oruviyex18.andmtproto.ProxyError
import io.github.oruviyex18.andmtproto.R

/** User-facing text for an error from the Python core, in the app's language. */
internal fun ProxyError.message(context: Context): String {
    val d = detail.orEmpty()
    return when (code) {
        "port_in_use" -> context.getString(R.string.error_port_in_use, d)
        "permission" -> context.getString(R.string.error_permission, d)
        "address" -> context.getString(R.string.error_address, d)
        "bad_secret" -> context.getString(R.string.error_bad_secret)
        "bad_port" -> context.getString(R.string.error_bad_port, d)
        "bad_dc_ip" -> context.getString(R.string.error_bad_dc_ip, d)
        "bad_fake_tls" -> context.getString(R.string.error_bad_fake_tls, d)
        else -> context.getString(R.string.error_unknown, detail ?: code)
    }
}
