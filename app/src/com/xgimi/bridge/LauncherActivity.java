package com.xgimi.bridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(64, 64, 64, 64);

        TextView title = new TextView(this);
        title.setText("Service started");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 12);

        String ip = getIpAddress();
        String host = ip != null ? ip : "<projector-ip>";
        String url = "http://" + host + ":8080";
        TextView subtitle = new TextView(this);
        String full = "Open " + url + " in your browser";
        SpannableString styled = new SpannableString(full);
        int urlStart = full.indexOf("http://");
        int urlEnd = urlStart + url.length();
        styled.setSpan(new StyleSpan(Typeface.BOLD), urlStart, urlEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        subtitle.setText(styled);
        subtitle.setTextColor(0xFFB3B3B3);
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(title, lp);
        root.addView(subtitle, lp);
        setContentView(root);

        BridgeJobService.scheduleSelf(this);
        Intent svc = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    private static String getIpAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            String fallback = null;
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual() || ni.isPointToPoint()) continue;
                String name = ni.getName() != null ? ni.getName().toLowerCase() : "";
                if (name.startsWith("p2p") || name.startsWith("tun") || name.startsWith("tap")
                        || name.startsWith("ppp") || name.startsWith("rmnet")
                        || name.startsWith("dummy") || name.startsWith("ap")) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) continue;
                    if (name.startsWith("wlan") || name.startsWith("eth")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
