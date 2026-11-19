package com.wayward.launcher;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One-shot, request-scoped acknowledgement gate for a native SAF directory batch. */
final class DirectoryBatchAcknowledgement {
    private int awaitingBatchIndex = -1;
    private CountDownLatch latch;
    private boolean pending;
    private boolean cancelled;
    private int acknowledgedBatchIndex = -1;
    private long deadlineNanos;

    synchronized CountDownLatch begin(int batchIndex, long timeout, TimeUnit unit) {
        if (cancelled || pending || batchIndex < 0 || timeout <= 0 || unit == null) return null;
        awaitingBatchIndex = batchIndex;
        acknowledgedBatchIndex = -1;
        latch = new CountDownLatch(1);
        pending = true;
        deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        return latch;
    }

    synchronized boolean acknowledge(int batchIndex) {
        if (cancelled || !pending || latch == null || awaitingBatchIndex != batchIndex) return false;
        if (System.nanoTime() >= deadlineNanos) {
            closePendingLocked();
            return false;
        }
        acknowledgedBatchIndex = batchIndex;
        closePendingLocked();
        return true;
    }

    synchronized boolean expire(int batchIndex) {
        if (!pending || awaitingBatchIndex != batchIndex) return false;
        acknowledgedBatchIndex = -1;
        closePendingLocked();
        return true;
    }

    synchronized boolean wasAcknowledged(int batchIndex) {
        return !cancelled && acknowledgedBatchIndex == batchIndex;
    }

    synchronized void cancel() {
        cancelled = true;
        acknowledgedBatchIndex = -1;
        closePendingLocked();
    }

    private void closePendingLocked() {
        pending = false;
        awaitingBatchIndex = -1;
        if (latch != null) latch.countDown();
    }
}
