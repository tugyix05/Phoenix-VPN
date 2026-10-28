package com.phoenix.phoenixvpn.network

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Firebase Realtime Database signal listener for feed changes.
 *
 * Scanner writes a tiny stamp to signals/feed on every successful
 * endpoints.json publish (see Scanner's FeedSignal). This listener
 * fires on attach with the CURRENT value (so attaching = an on-open
 * fetch) and again on every later change (so a publish while Phoenix
 * is open = an immediate fetch). The RTDB SDK auto-reconnects after
 * a network drop and re-fires, so an internet return also re-checks.
 *
 * Signal ONLY, never content: on every fire the caller still fetches
 * the endpoint list from GitHub, so a forged signal can at most
 * trigger one extra GitHub fetch, never inject endpoints.
 *
 * SETUP REQUIRED BEFORE THIS DOES ANYTHING (console step, not code):
 * 1. Create a Realtime Database in project phoenix-vpn-70e45.
 * 2. Set rules so ONLY this node is openly readable:
 *      { "rules": { "signals": { "feed": { ".read": true, ".write": true } } } }
 * 3. Fill RTDB_URL below with the database URL — the SAME value as
 *    Scanner's FeedSignal.RTDB_URL, e.g.
 *    https://phoenix-vpn-70e45-default-rtdb.asia-southeast1.firebasedatabase.app
 *    (region suffix depends on where the database was created).
 * If RTDB_URL were ever blank, start() returns false and the caller
 * falls back to a one-time on-open fetch.
 */
class FeedSignalListener(
    /** Called with the signal's "v" stamp (millis), or null if unreadable. */
    private val onSignal: (Long?) -> Unit
) {
    companion object {
        // Created 2026-10-28 via Firebase console (locked mode,
        // Singapore asia-southeast1, Spark plan, no billing).
        // Must match Scanner's FeedSignal.RTDB_URL exactly.
        const val RTDB_URL =
            "https://phoenix-vpn-70e45-default-rtdb.asia-southeast1.firebasedatabase.app"
    }

    /** Starts listening. Returns true if a listener is active. */
    fun start(): Boolean {
        if (RTDB_URL.isBlank()) return false
        return try {
            val ref = FirebaseDatabase.getInstance(RTDB_URL)
                .getReference("signals/feed")
            ref.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    onSignal(snapshot.child("v").getValue(Long::class.java))
                }

                override fun onCancelled(error: DatabaseError) {
                    // SDK retries on its own; the next attach re-fires.
                }
            })
            true
        } catch (_: Exception) {
            false
        }
    }
}
