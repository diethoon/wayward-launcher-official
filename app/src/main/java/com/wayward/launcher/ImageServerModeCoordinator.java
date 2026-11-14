package com.wayward.launcher;

/** Serializes all image server switches: stop remote, stop local, then start one target. */
final class ImageServerModeCoordinator {
    enum Mode { IMAGE_PACKS_ONLY, LOCAL_IMAGE_GENERATION, REMOTE_PC }

    interface Callback {
        void onSuccess();
        void onFailure(String message);
    }

    interface Operations {
        void stopRemote(Callback callback);
        void stopLocal(Callback callback);
        void startLocal(Callback callback);
        void startRemote(Callback callback);
        void persistMode(Mode mode);
        void onFinished(Mode mode, String error);
    }

    private boolean busy;

    synchronized boolean isBusy() { return busy; }

    void switchTo(Mode target, Operations operations) {
        if (target == null || operations == null) throw new IllegalArgumentException("전환 대상 또는 작업이 없습니다.");
        synchronized (this) {
            if (busy) {
                operations.onFinished(null, "이미 다른 서버 전환이 진행 중입니다.");
                return;
            }
            busy = true;
        }

        try {
            operations.stopRemote(new Callback() {
                @Override public void onSuccess() { stopLocal(target, operations, null); }
                @Override public void onFailure(String message) {
                    // Still stop the local server. Never start a new target while
                    // a previously active remote server failed to acknowledge stop.
                    stopLocal(target, operations, "원격 서버 중지 실패: " + message);
                }
            });
        } catch (Throwable error) {
            stopLocal(target, operations, "원격 서버 중지 예외: " + message(error));
        }
    }

    private void stopLocal(Mode target, Operations operations, String previousError) {
        try {
            operations.stopLocal(new Callback() {
                @Override public void onSuccess() {
                    if (previousError != null) {
                        finish(operations, null, previousError);
                    } else if (target == Mode.IMAGE_PACKS_ONLY) {
                        finish(operations, Mode.IMAGE_PACKS_ONLY, null);
                    } else {
                        startTarget(target, operations);
                    }
                }
                @Override public void onFailure(String message) {
                    String combined = previousError == null ? "로컬 서버 중지 실패: " + message
                            : previousError + " · 로컬 서버 중지 실패: " + message;
                    finish(operations, null, combined);
                }
            });
        } catch (Throwable error) {
            String combined = previousError == null ? "로컬 서버 중지 예외: " + message(error)
                    : previousError + " · 로컬 서버 중지 예외: " + message(error);
            finish(operations, null, combined);
        }
    }

    private void startTarget(Mode target, Operations operations) {
        Callback callback = new Callback() {
            @Override public void onSuccess() { finish(operations, target, null); }
            @Override public void onFailure(String message) {
                // Both old servers were stopped successfully, so a failed new start
                // returns to the safe image-pack-only mode.
                finish(operations, Mode.IMAGE_PACKS_ONLY, message);
            }
        };
        try {
            if (target == Mode.LOCAL_IMAGE_GENERATION) operations.startLocal(callback);
            else if (target == Mode.REMOTE_PC) operations.startRemote(callback);
            else finish(operations, Mode.IMAGE_PACKS_ONLY, null);
        } catch (Throwable error) {
            finish(operations, Mode.IMAGE_PACKS_ONLY, message(error));
        }
    }

    private void finish(Operations operations, Mode persistedMode, String error) {
        try {
            if (persistedMode != null) operations.persistMode(persistedMode);
        } catch (Throwable persistError) {
            error = "서버 모드 저장 실패: " + message(persistError);
            persistedMode = null;
        } finally {
            synchronized (this) { busy = false; }
        }
        operations.onFinished(persistedMode, error);
    }

    private static String message(Throwable error) {
        String value = error == null ? "" : error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
