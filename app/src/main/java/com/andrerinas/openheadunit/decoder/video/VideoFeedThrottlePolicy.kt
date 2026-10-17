package com.andrerinas.openheadunit.decoder.video

/**
 * Bounded patience when a compressed-frame queue is full. Only the video worker waits;
 * the shared socket reader continues dispatching audio and control traffic.
 *
 * Inter frames may be references for later pictures. Keeping their FIFO order and waiting for
 * input capacity avoids treating a brief decoder slowdown as packet loss. If the codec stays
 * stuck beyond the budget, the existing frame-loss recovery still requests a fresh keyframe.
 *
 * ACK follows video processing, not display completion. In the inspected Android Auto 17.8/18
 * Java sender, the legacy permit path can continue sending after a 300 ms wait expires, and the
 * >=6.0 protocol branch bypasses that permit path. Thus delayed ACKs can pace a sender but cannot
 * replace local count/byte limits. The transport and feeder both enforce their own bounds.
 */
object VideoFeedThrottlePolicy {

    /**
     * One timed offer on the queue. Between slices the caller re-checks whether the decoder is
     * still running, so a teardown aborts the wait within one slice rather than holding the stop
     * to the full budget.
     */
    const val OFFER_SLICE_MS = 50L

    /**
     * Total per-frame bound on the video worker.
     *
     * Must clear [VideoFeedQueuePolicy.INPUT_DEQUEUE_PATIENCE_MS]: the feed thread gives up on a
     * frame after that long and frees a slot, so any codec still draining at all - however slowly -
     * frees space inside this budget, and expiry genuinely means the feed side is stuck inside the
     * codec. Must stay under the 2s input-idle threshold the stall-cause classifier reads (a paced
     * video worker still stamps input liveness once per frame) and far under the 10s link-quiet
     * window the projection watchdog treats as a dead link.
     */
    const val WAIT_BUDGET_MS = 1_000L

    /** Whether the enqueue should keep waiting for space after [elapsedMs] on a live decoder. */
    fun shouldKeepWaiting(elapsedMs: Long, running: Boolean): Boolean =
        running && elapsedMs < WAIT_BUDGET_MS
}
