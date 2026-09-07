package dev.locklane.engine.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Whether a request comes straight from a browser on the engine's own machine (#781)
 * — the one condition under which the engine may open a window on the host's desktop
 * (a desktop IDE via {@code open-ide}; "Folder" joins it in #784). Two things must
 * both hold: the peer address the container saw is a loopback address, and the request
 * carries neither a {@code Forwarded} nor an {@code X-Forwarded-For} header — a request
 * relayed by a reverse proxy on the same machine arrives from loopback too, but the
 * proxy's header gives away that the browser is somewhere else, and that request must
 * never count as local. locklane is multi-user (ADR-105): a remote account must not be
 * able to pop editor windows on the host.
 *
 * <p>The peer address is judged as an IP literal only. {@link InetAddress#getByName}
 * would resolve a host name over DNS; a servlet container reports a literal, and
 * anything else is treated as not local rather than looked up.
 */
public final class LoopbackRequests {

    /** A dotted IPv4 literal, or an IPv6 literal (which always carries a colon, which no host name can). */
    private static final Pattern IP_LITERAL = Pattern.compile(
            "\\d{1,3}(\\.\\d{1,3}){3}|\\[?[0-9A-Fa-f.:]*:[0-9A-Fa-f.:]*\\]?");

    private LoopbackRequests() {
    }

    public static boolean isDirectLoopback(HttpServletRequest request) {
        if (request.getHeader("Forwarded") != null || request.getHeader("X-Forwarded-For") != null) {
            return false;
        }
        return isLoopbackLiteral(request.getRemoteAddr());
    }

    static boolean isLoopbackLiteral(String address) {
        if (address == null || !IP_LITERAL.matcher(address).matches()) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
