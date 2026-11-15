package com.wayward.launcher;

import java.net.InetAddress;

/** Public DNS-SD metadata used only to find a Host before its signed QR manifest is fetched. */
final class RemoteHostMdnsCandidate {
    static final String SERVICE_TYPE = "_wayward-remote-host._tcp.";
    final String hostId;
    final InetAddress address;
    final int port;
    final String publicKeyFingerprint;
    final String certificateSha256;

    RemoteHostMdnsCandidate(String hostId, InetAddress address, int port,
            String publicKeyFingerprint, String certificateSha256) {
        this.hostId = java.util.UUID.fromString(hostId).toString();
        if (address == null || address.getAddress().length != 4 || !isPrivateLanIpv4(address)) {
            throw new IllegalArgumentException("mDNS Host must resolve to a private IPv4 address.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("mDNS Host port is outside the valid range.");
        }
        this.address = address;
        this.port = port;
        this.publicKeyFingerprint = RemoteHostConnection.normalizeFingerprint(publicKeyFingerprint);
        this.certificateSha256 = RemoteHostConnection.normalizeFingerprint(certificateSha256);
    }

    private static boolean isPrivateLanIpv4(java.net.InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 &&
                (bytes[0] == 10 ||
                ((bytes[0] & 0xff) == 172 && (bytes[1] & 0xff) >= 16 && (bytes[1] & 0xff) <= 31) ||
                ((bytes[0] & 0xff) == 192 && (bytes[1] & 0xff) == 168));
    }
}
