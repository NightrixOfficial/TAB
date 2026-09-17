package net.effectsmp.tablist.resourcepack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.effectsmp.tablist.TablistPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * A tiny built-in HTTP server that serves the bundled resource pack
 * (pack/pack.zip in the plugin jar) so players' clients can download it.
 * <p>
 * This only works if the configured port is reachable from the internet
 * (forwarded, and not blocked by a firewall) -- see README.md.
 */
public class PackServer {

    private final TablistPlugin plugin;
    private HttpServer server;
    private byte[] packBytes;
    private String sha1Hex;

    public PackServer(TablistPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean start() {
        try (InputStream in = plugin.getResource("pack/pack.zip")) {
            if (in == null) {
                plugin.getLogger().warning("pack/pack.zip is missing from the plugin jar.");
                return false;
            }
            packBytes = in.readAllBytes();
            sha1Hex = sha1Hex(packBytes);
        } catch (IOException | NoSuchAlgorithmException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load the bundled resource pack.", e);
            return false;
        }

        int port = plugin.getConfig().getInt("header-image.port", 25566);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/pack.zip", this::handle);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Tablist-PackServer");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            plugin.getLogger().info("Resource pack server listening on port " + port + " (sha1 " + sha1Hex + ").");
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not bind the resource pack server to port " + port
                    + ". Is it already in use?", e);
            return false;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, packBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(packBytes);
            }
        } finally {
            exchange.close();
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static String sha1Hex(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        return HexFormat.of().formatHex(digest.digest(data));
    }

    /** Builds the download URL players will be sent, based on config. */
    public String buildUrl() {
        String publicAddress = plugin.getConfig().getString("header-image.public-address", "");
        if (publicAddress == null || publicAddress.isBlank()) {
            publicAddress = plugin.getServer().getIp();
        }
        if (publicAddress == null || publicAddress.isBlank()) {
            return null;
        }
        int port = plugin.getConfig().getInt("header-image.port", 25566);
        return "http://" + publicAddress + ":" + port + "/pack.zip";
    }

    public String getSha1Hex() {
        return sha1Hex;
    }
}
