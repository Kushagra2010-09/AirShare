package app.airshare.core

/**
 * Abuse safeguard for incoming sharing requests. Limits how many invitations may be pending at once
 * and how many may arrive within a sliding time window. Pure logic; time is injected for testing.
 */
class InviteGate(
    private val maxPending: Int = 3,
    private val maxPerWindow: Int = 6,
    private val windowMs: Long = 60_000L,
) {
    private val arrivals = ArrayDeque<Long>()
    private var pending = 0

    @Synchronized
    fun tryAdmit(nowMs: Long): Boolean {
        while (arrivals.isNotEmpty() && nowMs - arrivals.first() > windowMs) arrivals.removeFirst()
        if (pending >= maxPending || arrivals.size >= maxPerWindow) return false
        arrivals.addLast(nowMs)
        pending++
        return true
    }

    @Synchronized
    fun release() { if (pending > 0) pending-- }
}
