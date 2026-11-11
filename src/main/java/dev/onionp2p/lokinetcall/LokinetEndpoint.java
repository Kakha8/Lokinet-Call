package dev.onionp2p.lokinetcall;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Configures a persistent Lokinet SNApp key and discovers its published .loki name. */
final class LokinetEndpoint {
    private static final Pattern KEYFILE = Pattern.compile("(?im)^\\s*keyfile\\s*=\\s*(.+?)\\s*$");
    private static final Pattern LOKI_NAME = Pattern.compile("(?i)([a-z2-7]{50,60}\\.loki)");
    private static final String KEY_SETTING = "keyfile=";

    record Setup(Path configPath, Path keyPath, boolean restartRequired) { }

    private LokinetEndpoint() { }

    static Setup configure() throws IOException {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            throw new IOException("APPDATA is not set; cannot locate the Windows Lokinet configuration.");
        }

        Path base = Path.of(appData);
        Path config = firstExisting(base.resolve("Lokinet/lokinet.ini"),
                base.resolve(".lokinet/lokinet.ini"), base.resolve("lokinet/lokinet.ini"));
        if (config == null) {
            // Current Windows packages use %APPDATA%\\Lokinet; older builds used .lokinet.
            config = base.resolve("Lokinet/lokinet.ini");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "[network]" + System.lineSeparator(), StandardCharsets.UTF_8);
        }

        String ini = Files.readString(config, StandardCharsets.UTF_8);
        Matcher existing = KEYFILE.matcher(ini);
        if (existing.find()) {
            String configured = existing.group(1).trim().replace("\\\\", "\\");
            Path keyPath = Path.of(configured);
            if (!keyPath.isAbsolute()) keyPath = config.getParent().resolve(keyPath).normalize();
            return new Setup(config, keyPath, false);
        }

        Path key = config.getParent().resolve("onionp2p-call-endpoint.private");
        String normalizedKey = key.toString().replace("\\", "/");
        int networkHeader = sectionHeader(ini, "network");
        String setting = KEY_SETTING + normalizedKey + System.lineSeparator();
        if (networkHeader >= 0) {
            int insertAt = ini.indexOf('\n', networkHeader);
            if (insertAt < 0) ini += System.lineSeparator() + setting;
            else ini = ini.substring(0, insertAt + 1) + setting + ini.substring(insertAt + 1);
        } else {
            if (!ini.isEmpty() && !ini.endsWith("\n")) ini += System.lineSeparator();
            ini += System.lineSeparator() + "[network]" + System.lineSeparator() + setting;
        }
        Files.writeString(config, ini, StandardCharsets.UTF_8);
        return new Setup(config, key, true);
    }

    static String findAddress() {
        // Lokinet exposes the locally hosted SNApp name through its DNS resolver as localhost.loki.
        for (String dns : new String[]{"127.0.0.1", "127.3.2.1"}) {
            try {
                Process process = new ProcessBuilder("nslookup", "-type=cname", "localhost.loki", dns)
                        .redirectErrorStream(true).start();
                boolean finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    continue;
                }
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                Matcher match = LOKI_NAME.matcher(output);
                if (match.find()) return match.group(1).toLowerCase(Locale.ROOT);
            } catch (IOException ignored) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static Path firstExisting(Path... paths) {
        for (Path path : paths) if (Files.isRegularFile(path)) return path;
        return null;
    }

    private static int sectionHeader(String ini, String section) {
        Matcher matcher = Pattern.compile("(?im)^\\s*\\[" + Pattern.quote(section) + "\\]\\s*$").matcher(ini);
        return matcher.find() ? matcher.start() : -1;
    }
}
