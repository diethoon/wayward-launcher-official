package com.wayward.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public final class DirectoryBatchAcknowledgementTest {
    @Test public void acknowledgementIsOneShotAndBatchScoped() throws Exception {
        DirectoryBatchAcknowledgement gate = new DirectoryBatchAcknowledgement();
        CountDownLatch first = gate.begin(0, 1, TimeUnit.SECONDS);
        assertNotNull(first);
        assertFalse(gate.acknowledge(1));
        assertTrue(gate.acknowledge(0));
        assertTrue(first.await(10, TimeUnit.MILLISECONDS));
        assertFalse("duplicate ACK must not be accepted", gate.acknowledge(0));

        CountDownLatch second = gate.begin(1, 1, TimeUnit.SECONDS);
        assertNotNull(second);
        assertFalse("stale ACK must not release the next batch", gate.acknowledge(0));
        assertTrue(gate.acknowledge(1));
    }

    @Test public void timeoutClosesTheAckWindowAndReleasesTheWorker() throws Exception {
        DirectoryBatchAcknowledgement gate = new DirectoryBatchAcknowledgement();
        CountDownLatch waiting = gate.begin(7, 1, TimeUnit.MILLISECONDS);
        assertNotNull(waiting);
        Thread.sleep(10);
        assertFalse("late ACK after deadline must be rejected", gate.acknowledge(7));
        assertTrue("expired wait must be released", waiting.await(10, TimeUnit.MILLISECONDS));
        assertFalse(gate.expire(7));
        assertFalse(gate.acknowledge(7));
    }

    @Test public void explicitExpiryRejectsLateAndDuplicateAcknowledgements() throws Exception {
        DirectoryBatchAcknowledgement gate = new DirectoryBatchAcknowledgement();
        CountDownLatch waiting = gate.begin(3, 1, TimeUnit.SECONDS);
        assertNotNull(waiting);
        assertTrue(gate.expire(3));
        assertTrue(waiting.await(10, TimeUnit.MILLISECONDS));
        assertFalse(gate.acknowledge(3));
        assertFalse(gate.acknowledge(3));
    }

    @Test public void cancellationReleasesPendingBatchAndPreventsReuse() throws Exception {
        DirectoryBatchAcknowledgement gate = new DirectoryBatchAcknowledgement();
        CountDownLatch waiting = gate.begin(2, 1, TimeUnit.SECONDS);
        assertNotNull(waiting);
        gate.cancel();
        assertTrue(waiting.await(10, TimeUnit.MILLISECONDS));
        assertFalse(gate.acknowledge(2));
        assertNull(gate.begin(3, 1, TimeUnit.SECONDS));
        gate.cancel();
    }
}
