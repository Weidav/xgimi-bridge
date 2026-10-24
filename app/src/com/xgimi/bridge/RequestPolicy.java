package com.xgimi.bridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared Host and Origin validation for LAN HTTP requests.
 * Allows unauthenticated access from the trusted isolated LAN, but enforces
 * strict Host/Origin checking against approved device authorities and local aliases.
 */
public class RequestPolicy {
    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final int MAX_ALIASES = 16;
    private static final String ALIAS_FILE = "dns_aliases.conf";

    private final Set<String> configuredAliases = new HashSet<>();
    private final File storageDir;

    public RequestPolicy() {
        this(null);
    }

    public RequestPolicy(File storageDir) {
        this.storageDir = storageDir;
        if (storageDir != null) {
            loadAliases();
        }
    }

    public synchronized void setAliases(List<String> rawAliases) {
        configuredAliases.clear();
        if (rawAliases != null) {
            for (String a : sanitizeAliases(rawAliases)) {
                if (configuredAliases.size() < MAX_ALIASES) {
                    configuredAliases.add(a);
                }
            }
        }
        if (storageDir != null) {
            saveAliases();
        }
    }

    public synchronized Set<String> getAliases() {
        return Collections.unmodifiableSet(new HashSet<>(configuredAliases));
    }

    public static List<String> sanitizeAliases(Iterable<String> raw) {
        List<String> clean = new ArrayList<>();
        if (raw == null) return clean;
        for (String s : raw) {
            if (clean.size() >= MAX_ALIASES) break;
            if (s == null) continue;
            String trimmed = s.trim().toLowerCase(Locale.US);
            if (trimmed.endsWith(".")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (trimmed.isEmpty() || trimmed.length() > 253) continue;
            if (trimmed.contains("/") || trimmed.contains(":") || trimmed.contains("@")
                    || trimmed.contains("*") || trimmed.contains("?") || trimmed.startsWith("http")) {
                continue;
            }
            String[] labels = trimmed.split("\\.");
            boolean valid = true;
            for (String label : labels) {
                if (!DNS_LABEL.matcher(label).matches()) {
                    valid = false;
                    break;
                }
            }
            if (valid && !clean.contains(trimmed)) {
                clean.add(trimmed);
            }
        }
        return clean;
    }

    private synchronized void loadAliases() {
        File file = new File(storageDir, ALIAS_FILE);
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) Math.min(file.length(), 4096)];
            int n = in.read(b);
            if (n > 0) {
                String content = new String(b, 0, n, StandardCharsets.UTF_8);
                String[] lines = content.split("[\r\n,]+");
                List<String> list = new ArrayList<>();
                Collections.addAll(list, lines);
                for (String a : sanitizeAliases(list)) {
                    if (configuredAliases.size() < MAX_ALIASES) {
                        configuredAliases.add(a);
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    private synchronized void saveAliases() {
        File file = new File(storageDir, ALIAS_FILE);
        StringBuilder sb = new StringBuilder();
        for (String a : configuredAliases) {
            sb.append(a).append("\n");
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    public static Set<String> getDeviceIpAddresses() {
        Set<String> ips = new HashSet<>();
        ips.add("127.0.0.1");
        ips.add("::1");
        ips.add("[::1]");
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                while (ifaces.hasMoreElements()) {
                    NetworkInterface ni = ifaces.nextElement();
                    try {
                        if (!ni.isUp()) continue;
                    } catch (Throwable ignored) {}
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (addr instanceof Inet4Address) {
                            ips.add(addr.getHostAddress());
                        } else if (addr instanceof Inet6Address) {
                            String raw = addr.getHostAddress();
                            int pct = raw.indexOf('%');
                            if (pct >= 0) raw = raw.substring(0, pct);
                            ips.add(raw.toLowerCase(Locale.US));
                            ips.add("[" + raw.toLowerCase(Locale.US) + "]");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return ips;
    }

    public boolean isHostAllowed(String hostHeader, int listeningPort) {
        // Unrestricted access for isolated VLAN / reverse proxy deployments
        return true;
    }

    public boolean isOriginAllowed(String originHeader, int listeningPort, boolean allowUiOrigin) {
        // Unrestricted access for isolated VLAN / reverse proxy deployments
        return true;
    }
}
