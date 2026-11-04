package io.datasync.sink.webhook;

import io.datasync.api.SinkException;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF protection (design 11). Link-local addresses, which include cloud metadata services (169.254.169.254),
 * and wildcard and multicast addresses are always refused. Loopback and private networks are allowed, since
 * webhook consumers are usually internal (design decision 10), unless the system property
 * {@code datasync.webhook.block-private-networks} or the environment variable
 * {@code DATASYNC_WEBHOOK_BLOCK_PRIVATE_NETWORKS} is "true".
 */
final class AddressGuard {

    private AddressGuard() {}

    static void check(URI url) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(url.getHost());
        } catch (UnknownHostException e) {
            throw SinkException.retryable("unknown host " + url.getHost(), null, e);
        }
        boolean blockPrivate = Boolean.parseBoolean(System.getProperty("datasync.webhook.block-private-networks",
            System.getenv().getOrDefault("DATASYNC_WEBHOOK_BLOCK_PRIVATE_NETWORKS", "false")));
        for (InetAddress a : addresses) {
            if (a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) {
                throw SinkException.permanent("refusing " + url.getHost() + " (" + a.getHostAddress() + "): link-local, wildcard or multicast address", null);
            }
            boolean uniqueLocal = a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;
            if (blockPrivate && (a.isLoopbackAddress() || a.isSiteLocalAddress() || uniqueLocal)) {
                throw SinkException.permanent("refusing " + url.getHost() + " (" + a.getHostAddress() + "): private networks are blocked", null);
            }
        }
    }
}
