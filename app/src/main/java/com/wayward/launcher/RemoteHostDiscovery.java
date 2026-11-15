package com.wayward.launcher;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Non-UI LAN discovery for an already QR-trusted Remote Host.
 *
 * mDNS TXT records are discovery hints only. The candidate is accepted only when its
 * Host ID, public-key fingerprint and TLS certificate fingerprint match the saved QR
 * trust anchor. The new IP is then contacted with that old pin, and its fresh signed
 * connection manifest is validated before the stored endpoint is updated.
 */
public final class RemoteHostDiscovery implements AutoCloseable {
    public interface Listener {
        void onHostAddressUpdated(RemoteHostConnection connection);
        void onHostLost(String hostId);
        void onDiscoveryError(String operation, Exception error);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService verifier = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Wayward-RemoteHost-Discovery");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> resolving = new HashSet<>();
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private Listener listener;
    private RemoteHostConnection trustedHost;
    private boolean closed;
    private boolean started;

    public RemoteHostDiscovery(Context context) {
        if (context == null) throw new IllegalArgumentException("Android Context is required.");
        this.appContext = context.getApplicationContext();
    }

    /** Starts discovery. A real QR-trusted Host must already exist in RemoteHostStore. */
    public synchronized void start(Listener callback) throws Exception {
        if (closed) throw new IllegalStateException("RemoteHostDiscovery is closed.");
        if (started) return;
        if (callback == null) throw new IllegalArgumentException("Discovery callback is required.");

        RemoteHostConnection saved = RemoteHostStore.loadConnection(appContext);
        if (saved == null) {
            throw new SecurityException(
                    "mDNS cannot establish first trust. Scan the QR displayed by the Windows Manager first.");
        }

        NsdManager manager = appContext.getSystemService(NsdManager.class);
        if (manager == null) throw new IllegalStateException("Android NSD discovery is unavailable.");

        this.trustedHost = saved;
        this.listener = callback;
        this.nsdManager = manager;
        this.discoveryListener = createDiscoveryListener();
        this.started = true;
        try {
            manager.discoverServices(
                    RemoteHostMdnsCandidate.SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener);
        } catch (RuntimeException error) {
            started = false;
            discoveryListener = null;
            listener = null;
            throw error;
        }
    }

    public synchronized boolean isRunning() {
        return started && !closed;
    }

    private NsdManager.DiscoveryListener createDiscoveryListener() {
        return new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {
                // Deliberately no UI status: this service reports only resolved Hosts.
            }

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo == null || serviceInfo.getServiceType() == null ||
                        !serviceInfo.getServiceType().toLowerCase(java.util.Locale.ROOT)
                                .contains("_wayward-remote-host._tcp")) {
                    return;
                }
                NsdManager manager;
                synchronized (RemoteHostDiscovery.this) {
                    if (!started || closed || nsdManager == null) return;
                    manager = nsdManager;
                }
                try {
                    manager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                            report("resolve", new IllegalStateException("NSD resolve failed: " + errorCode));
                        }

                        @Override public void onServiceResolved(NsdServiceInfo resolved) {
                            resolveCandidate(resolved);
                        }
                    });
                } catch (RuntimeException error) {
                    report("resolve", error);
                }
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
                RemoteHostConnection trusted;
                synchronized (RemoteHostDiscovery.this) { trusted = trustedHost; }
                if (trusted == null || serviceInfo == null) return;
                String lostHostId = txt(serviceInfo.getAttributes(), "hostid");
                if (lostHostId != null && trusted.hostId.equalsIgnoreCase(lostHostId)) {
                    reportHostLost(trusted.hostId);
                }
            }

            @Override public void onDiscoveryStopped(String serviceType) {
                synchronized (RemoteHostDiscovery.this) { started = false; }
            }

            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                synchronized (RemoteHostDiscovery.this) { started = false; }
                report("start", new IllegalStateException("NSD discovery start failed: " + errorCode));
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                report("stop", new IllegalStateException("NSD discovery stop failed: " + errorCode));
            }
        };
    }

    private void resolveCandidate(NsdServiceInfo info) {
        if (info == null) return;
        final RemoteHostMdnsCandidate candidate;
        try {
            InetAddress address = info.getHost();
            Map<String, byte[]> txt = info.getAttributes();
            String hostId = txt(txt, "hostid");
            String keyFingerprint = txt(txt, "keyfp");
            String certificateFingerprint = txt(txt, "certfp");
            String protocol = txt(txt, "protocol");
            if (!"1".equals(protocol)) return;
            candidate = new RemoteHostMdnsCandidate(
                    hostId, address, info.getPort(), keyFingerprint, certificateFingerprint);
        } catch (Exception ignored) {
            // mDNS is unauthenticated. Malformed or non-private candidates are silently rejected.
            return;
        }

        final RemoteHostConnection pinned;
        final String key = candidate.hostId + "@" + candidate.address.getHostAddress() + ":" + candidate.port;
        synchronized (this) {
            if (!started || closed || trustedHost == null || !resolving.add(key)) return;
            pinned = trustedHost;
        }

        // Do not disclose stored Host details to any LAN peer. Only matching TXT metadata
        // is sent through the verifier, and the HTTPS connection uses the saved certificate pin.
        if (!pinned.hostId.equalsIgnoreCase(candidate.hostId) ||
                !pinned.publicKeyFingerprint.equalsIgnoreCase(candidate.publicKeyFingerprint) ||
                !pinned.certificateSha256.equalsIgnoreCase(candidate.certificateSha256)) {
            synchronized (this) { resolving.remove(key); }
            return;
        }

        verifier.execute(() -> {
            try {
                RemoteHostConnection refreshed = RemoteHostClient.discoverHost(candidate, pinned);
                RemoteHostStore.saveConnection(appContext, refreshed);
                synchronized (this) {
                    if (closed || !started) return;
                    trustedHost = refreshed;
                }
                Listener target = currentListener();
                if (target != null) mainHandler.post(() -> target.onHostAddressUpdated(refreshed));
            } catch (Exception error) {
                report("verify-host", error);
            } finally {
                synchronized (this) { resolving.remove(key); }
            }
        });
    }

    private static String txt(Map<String, byte[]> attributes, String key) {
        if (attributes == null) return null;
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                return new String(entry.getValue(), StandardCharsets.UTF_8).trim();
            }
        }
        return null;
    }

    private synchronized Listener currentListener() {
        return closed ? null : listener;
    }

    private void report(String operation, Exception error) {
        Listener target = currentListener();
        if (target != null) mainHandler.post(() -> target.onDiscoveryError(operation, error));
    }

    private void reportHostLost(String hostId) {
        Listener target = currentListener();
        if (target != null) mainHandler.post(() -> target.onHostLost(hostId));
    }

    public synchronized void stop() {
        if (closed || !started) return;
        started = false;
        NsdManager manager = nsdManager;
        NsdManager.DiscoveryListener active = discoveryListener;
        discoveryListener = null;
        if (manager != null && active != null) {
            try { manager.stopServiceDiscovery(active); }
            catch (IllegalArgumentException ignored) { }
            catch (RuntimeException error) { report("stop", error); }
        }
        resolving.clear();
    }

    @Override public synchronized void close() {
        if (closed) return;
        stop();
        closed = true;
        listener = null;
        trustedHost = null;
        verifier.shutdownNow();
    }
}
