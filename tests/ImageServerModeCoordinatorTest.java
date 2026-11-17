package com.wayward.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ImageServerModeCoordinatorTest {
    private static final class Fake implements ImageServerModeCoordinator.Operations {
        final List<String> events = new ArrayList<>();
        ImageServerModeCoordinator.Mode mode = ImageServerModeCoordinator.Mode.IMAGE_PACKS_ONLY;
        String failureAt = "";
        @Override public void stopRemote(ImageServerModeCoordinator.Callback cb) {
            events.add("stop-remote");
            if ("stop-remote".equals(failureAt)) cb.onFailure("remote-stop-failed"); else cb.onSuccess();
        }
        @Override public void stopLocal(ImageServerModeCoordinator.Callback cb) {
            events.add("stop-local");
            if ("stop-local".equals(failureAt)) cb.onFailure("local-stop-failed"); else cb.onSuccess();
        }
        @Override public void startLocal(ImageServerModeCoordinator.Callback cb) {
            events.add("start-local");
            if ("start-local".equals(failureAt)) cb.onFailure("local-start-failed"); else cb.onSuccess();
        }
        @Override public void startRemote(ImageServerModeCoordinator.Callback cb) {
            events.add("start-remote");
            if ("start-remote".equals(failureAt)) cb.onFailure("remote-start-failed"); else cb.onSuccess();
        }
        @Override public void persistMode(ImageServerModeCoordinator.Mode next) {
            events.add("persist-" + next.name());
            mode = next;
        }
        @Override public void onFinished(ImageServerModeCoordinator.Mode result, String error) {
            events.add(error == null ? "done-" + result.name() : "error-" + error);
        }
    }

    public static void main(String[] args) {
        ImageServerModeCoordinator coordinator = new ImageServerModeCoordinator();
        Fake local = new Fake();
        coordinator.switchTo(ImageServerModeCoordinator.Mode.LOCAL_IMAGE_GENERATION, local);
        assertEquals(Arrays.asList("stop-remote","stop-local","start-local",
                "persist-LOCAL_IMAGE_GENERATION","done-LOCAL_IMAGE_GENERATION"), local.events);
        if (coordinator.isBusy()) throw new AssertionError("coordinator left busy after local start");

        Fake packs = new Fake();
        coordinator.switchTo(ImageServerModeCoordinator.Mode.IMAGE_PACKS_ONLY, packs);
        assertEquals(Arrays.asList("stop-remote","stop-local","persist-IMAGE_PACKS_ONLY",
                "done-IMAGE_PACKS_ONLY"), packs.events);

        Fake remote = new Fake();
        coordinator.switchTo(ImageServerModeCoordinator.Mode.REMOTE_PC, remote);
        assertEquals(Arrays.asList("stop-remote","stop-local","start-remote",
                "persist-REMOTE_PC","done-REMOTE_PC"), remote.events);

        Fake failedStart = new Fake();
        failedStart.failureAt = "start-local";
        coordinator.switchTo(ImageServerModeCoordinator.Mode.LOCAL_IMAGE_GENERATION, failedStart);
        assertEquals(Arrays.asList("stop-remote","stop-local","start-local",
                "persist-IMAGE_PACKS_ONLY","error-local-start-failed"), failedStart.events);

        Fake failedRemoteStop = new Fake();
        failedRemoteStop.failureAt = "stop-remote";
        coordinator.switchTo(ImageServerModeCoordinator.Mode.LOCAL_IMAGE_GENERATION, failedRemoteStop);
        assertEquals(Arrays.asList("stop-remote","stop-local",
                "error-원격 서버 중지 실패: remote-stop-failed"), failedRemoteStop.events);
        if (coordinator.isBusy()) throw new AssertionError("coordinator left busy after remote stop failure");

        Fake failedLocalStop = new Fake();
        failedLocalStop.failureAt = "stop-local";
        coordinator.switchTo(ImageServerModeCoordinator.Mode.IMAGE_PACKS_ONLY, failedLocalStop);
        assertEquals(Arrays.asList("stop-remote","stop-local",
                "error-로컬 서버 중지 실패: local-stop-failed"), failedLocalStop.events);
        if (coordinator.isBusy()) throw new AssertionError("coordinator left busy after local stop failure");
        System.out.println("ImageServerModeCoordinatorTest passed");
    }

    private static void assertEquals(List<String> expected, List<String> actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
