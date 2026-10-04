import android.net.Uri;
import com.google.android.gms.cast.CastDevice;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.URI;

public final class CastDeviceIconCheck {
    private static int failures;

    private static void check(boolean value, String message) {
        if (!value) { failures++; System.out.println("FAIL " + message); }
    }

    private static void exercise(String name, InetAddress address, String path, boolean expectIcon) throws Exception {
        CastDevice device = new CastDevice("id", "receiver", address, 8009, "1", "Room", "Cast", path, 0, 5);
        check(device.getAddress().equals(address.getHostAddress()), name + " raw device address unchanged");
        check(device.getServicePort() == 8009, name + " Cast service port unchanged");
        check(device.hasIcons() == expectIcon, name + " icon presence");
        if (!expectIcon) {
            check(device.getIcons().isEmpty(), name + " empty icon list");
            System.out.println("CASE " + name + " no icon, rawAddress=" + device.getAddress());
            return;
        }
        check(device.getIcons().size() == 1, name + " exactly one icon");
        Uri androidUri = device.getIcons().get(0).getUrl();
        String actual = androidUri.toString();
        String expectedHost = address instanceof Inet6Address ? "[" + address.getHostAddress().replace("%", "%25") + "]" : address.getHostAddress();
        String expected = "http://" + expectedHost + ":8008" + path;
        check(actual.equals(expected), name + " HTTP URL authority encoding: " + actual);
        check((expectedHost + ":8008").equals(androidUri.getEncodedAuthority()), name + " Android URI encoded authority");
        try {
            URI uri = new URI(actual).parseServerAuthority();
            check(uri.getHost().equals(expectedHost), name + " Java URI host");
            check(uri.getPort() == 8008, name + " Java URI port");
            check("/setup/icon.png".equals(uri.getRawPath()), name + " path remains intact");
            check("color=red%20blue&size=32".equals(uri.getRawQuery()), name + " encoded query remains intact");
        } catch (Exception error) {
            failures++;
            System.out.println("FAIL " + name + " strict HTTP authority parsing: " + error);
        }
        System.out.println("CASE " + name + " url=" + actual + " rawAddress=" + device.getAddress());
    }

    private static void loopbackFetch() throws Exception {
        InetAddress loopback = InetAddress.getByName("::1");
        byte[] payload = "cast-icon-ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.concurrent.CompletableFuture<String> request = new java.util.concurrent.CompletableFuture<>();
        try (java.net.ServerSocket server = new java.net.ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(loopback, 8008));
            server.setSoTimeout(3000);
            Thread receiver = new Thread(() -> {
                try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                    String requestLine = reader.readLine();
                    for (String header; (header = reader.readLine()) != null && !header.isEmpty();) { }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(payload);
                    socket.getOutputStream().flush();
                    request.complete(requestLine);
                } catch (Throwable error) { request.completeExceptionally(error); }
            }, "cast-icon-loopback");
            receiver.setDaemon(true);
            receiver.start();
            CastDevice device = new CastDevice("id", "receiver", loopback, 8009, "1", "Room", "Cast", "/setup/icon.png?size=32#preview", 0, 5);
            String actual = device.getIcons().get(0).getUrl().toString();
            java.net.URLConnection connection = new java.net.URL(actual).openConnection(java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            try (java.io.InputStream input = connection.getInputStream()) {
                check(java.util.Arrays.equals(payload, input.readAllBytes()), "loopback fetched exact response bytes");
            }
            String line = request.get(3, java.util.concurrent.TimeUnit.SECONDS);
            check("GET /setup/icon.png?size=32 HTTP/1.1".equals(line), "loopback request path and query: " + line);
            System.out.println("CASE IPv6-loopback url=" + actual + " request=" + line + " responseBytes=" + payload.length);
        }
    }

    public static void main(String[] args) throws Exception {
        String path = "/setup/icon.png?color=red%20blue&size=32";
        exercise("IPv4", InetAddress.getByAddress(new byte[] {(byte)192, (byte)168, 1, 23}), path, true);
        exercise("IPv6", InetAddress.getByName("2001:db8::1"), path, true);
        byte[] linkLocal = new byte[16]; linkLocal[0] = (byte)0xfe; linkLocal[1] = (byte)0x80; linkLocal[15] = 1;
        exercise("IPv6-scoped", Inet6Address.getByAddress(null, linkLocal, 3), path, true);
        exercise("IPv6-no-icon", InetAddress.getByName("2001:db8::1"), null, false);
        if (args.length > 0 && args[0].equals("--loopback")) loopbackFetch();
        if (failures != 0) throw new AssertionError(failures + " failures");
        System.out.println("PASS all four production CastDevice constructor cases");
    }
}
