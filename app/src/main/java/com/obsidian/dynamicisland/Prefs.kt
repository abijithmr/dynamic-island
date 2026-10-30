package com.obsidian.dynamicisland

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("island", Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = sp(c).getBoolean("enabled", false)

    fun setEnabled(c: Context, value: Boolean) {
        sp(c).edit().putBoolean("enabled", value).apply()
    }
}
