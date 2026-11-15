package com.wayward.launcher;

import java.net.InetAddress;

/** Public DNS-SD metadata used only to find a Host before its signed QR manifest is fetched. */
final class RemoteHostMdnsCandidate {
    final String hostId;
    final InetAddress address;
    final int port;
    final String publicKeyFingerprint;
    final String certificateSha256;

    RemoteHostMdnsCandidate(String hostId, InetAddress address, int port,
            String publicKeyFingerprint, String certificateSha256) {
        this.hostId = hostId;
        this.address = address;
        this.port = port;
        this.publicKeyFingerprint = RemoteHostConnection.normalizeFingerprint(publicKeyFingerprint);
        this.certificateSha256 = RemoteHostConnection.normalizeFingerprint(certificateSha256);
    }
}
