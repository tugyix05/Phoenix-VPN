package com.phoenix.phoenixvpn.network

import android.content.Context

/**
 * v1.75: the two remote-configurable links (user spec 2026-11-19).
 *
 * Resolution order, highest first:
 *   1. per-device override (set from the long-press Log editor) — this phone only
 *   2. last-known feed value (cached on the phone from endpoints.json)
 *   3. hardcoded default (the pre-1.75 value — identical behaviour when the
 *      feed carries no fields)
 *
 * Feed values are cached only when they look like an http(s) URL; a feed
 * that omits the fields (or carries a bad value) never clears the cache
 * and never fails the fetch — the next tier simply answers instead.
 * Pure SharedPreferences reads/writes; no network, no side effects.
 */
object LinkConfig {

    /** Pre-1.75 generator URL (ConfigService.GENERATE_URL). */
    const val DEFAULT_GENERATOR_URL = "https://pguard.val.run/"

    /** Pre-1.75 update-download URL (UpdateService.LOVABLE_DOWNLOAD_URL). */
    const val DEFAULT_UPDATE_URL =
        "https://ipmh.lovable.app/api/public/apk/phoenix-vpn-v18"

    private const val PREFS = "phoenix_link_config"
    private const val KEY_OVERRIDE_GENERATOR = "override_generator_url"
    private const val KEY_OVERRIDE_UPDATE = "override_update_url"
    private const val KEY_FEED_GENERATOR = "feed_generator_url"
    private const val KEY_FEED_UPDATE = "feed_update_url"

    fun generatorUrl(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY_OVERRIDE_GENERATOR, null)?.takeIf { it.isNotBlank() }
            ?: p.getString(KEY_FEED_GENERATOR, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_GENERATOR_URL
    }

    fun updateUrl(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY_OVERRIDE_UPDATE, null)?.takeIf { it.isNotBlank() }
            ?: p.getString(KEY_FEED_UPDATE, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_UPDATE_URL
    }

    fun hasOverride(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.contains(KEY_OVERRIDE_GENERATOR) || p.contains(KEY_OVERRIDE_UPDATE)
    }

    /** Cache the feed's link values (called only on a successful feed fetch). */
    fun applyFeed(context: Context, generatorUrl: String?, updateUrl: String?) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        var changed = false
        if (isUsableUrl(generatorUrl)) {
            e.putString(KEY_FEED_GENERATOR, generatorUrl!!.trim())
            changed = true
        }
        if (isUsableUrl(updateUrl)) {
            e.putString(KEY_FEED_UPDATE, updateUrl!!.trim())
            changed = true
        }
        if (changed) e.apply()
    }

    /**
     * Per-device override from the long-press Log editor. A blank value for
     * one field removes that field's override (falls back to feed/default);
     * both blank removes the whole override.
     */
    fun setOverride(context: Context, generatorUrl: String, updateUrl: String) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val g = generatorUrl.trim()
        val u = updateUrl.trim()
        if (isUsableUrl(g)) e.putString(KEY_OVERRIDE_GENERATOR, g)
        else e.remove(KEY_OVERRIDE_GENERATOR)
        if (isUsableUrl(u)) e.putString(KEY_OVERRIDE_UPDATE, u)
        else e.remove(KEY_OVERRIDE_UPDATE)
        e.apply()
    }

    /** Back to feed/default for both links. */
    fun clearOverride(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_OVERRIDE_GENERATOR)
            .remove(KEY_OVERRIDE_UPDATE)
            .apply()
    }

    private fun isUsableUrl(v: String?): Boolean {
        if (v.isNullOrBlank()) return false
        val t = v.trim()
        return t.startsWith("http://") || t.startsWith("https://")
    }
}
