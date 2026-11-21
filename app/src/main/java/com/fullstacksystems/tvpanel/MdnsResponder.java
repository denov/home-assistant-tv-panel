package com.fullstacksystems.tvpanel;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * Announces {@link Mdns.Service} on the LAN and answers queries for it, on a multicast socket shared with the
 * system's own responders (SO_REUSEADDR). Announces twice on start, a goodbye on stop. Off the main thread.
 */
final class MdnsResponder {
    private final Context ctx;
    private final Mdns.Service service;
    private MulticastSocket socket;
    private WifiManager.MulticastLock lock;
    private Thread thread;
    private volatile boolean running;

    MdnsResponder(Context ctx, Mdns.Service service) {
        this.ctx = ctx.getApplicationContext();
        this.service = service;
    }

    /** The interface and IPv4 address to announce: the first that's up, not loopback, with an IPv4 address. */
    static Inet4Address localIpv4(NetworkInterface[] chosen) {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || !n.supportsMulticast()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        if (chosen != null) chosen[0] = n;
                        return (Inet4Address) a;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // No network.
        }
        return null;
    }

    void start() {
        running = true;
        thread = new Thread(this::run, "mdns");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        MulticastSocket s = socket;
        if (s != null) {
            // The goodbye goes out on a short-lived thread: stop() runs on the main thread, which mustn't do network.
            new Thread(() -> {
                try {
                    send(s, Mdns.response(service, true));
                } catch (IOException ignored) {
                    // Leaving anyway; listeners drop the record when its TTL runs out.
                }
                s.close();
            }, "mdns-bye").start();
        }
        socket = null;
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
    }

    private void run() {
        NetworkInterface[] nif = new NetworkInterface[1];
        Inet4Address ip = localIpv4(nif);
        if (ip == null) {
            android.util.Log.w("TvPanelMdns", "no IPv4 interface with multicast");
            return;
        }
        try {
            WifiManager wifi = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("tvpanel-mdns");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (RuntimeException ignored) {
            // Ethernet TVs don't need it.
        }
        try {
            MulticastSocket s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Mdns.PORT));
            s.setTimeToLive(255);
            // Android routes each network through its own table: without binding to the network, IPv4 multicast
            // from this (dual-stack) socket goes nowhere, silently. Set the IPv4 output interface as well as the IPv6 one.
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network net = cm == null ? null : cm.getActiveNetwork();
            if (net != null) net.bindSocket(s);
            s.setNetworkInterface(nif[0]);
            //noinspection deprecation
            s.setInterface(ip);
            s.joinGroup(new InetSocketAddress(InetAddress.getByName(Mdns.GROUP), Mdns.PORT), nif[0]);
            socket = s;
            byte[] hello = Mdns.response(service, false);
            send(s, hello);
            android.util.Log.i("TvPanelMdns", "announced " + service.instanceName() + " on " + nif[0].getName());
            Thread.sleep(1000);
            if (running) send(s, hello);
            byte[] buf = new byte[9000];
            while (running) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                s.receive(in);
                List<Mdns.Question> qs = Mdns.questions(in.getData(), in.getLength());
                for (Mdns.Question q : qs) {
                    if (q.name.contains("tvpanel")) {
                        android.util.Log.i("TvPanelMdns", "query " + q.name + " type " + q.type + " from " + in.getAddress());
                    }
                }
                if (Mdns.asksFor(qs, service)) {
                    send(s, hello);
                    android.util.Log.i("TvPanelMdns", "answered");
                }
            }
        } catch (IOException | InterruptedException e) {
            // Closed by stop(), or no multicast on this network.
            if (running) android.util.Log.w("TvPanelMdns", "mDNS stopped", e);
        }
    }

    private static void send(MulticastSocket s, byte[] data) throws IOException {
        s.send(new DatagramPacket(data, data.length, InetAddress.getByName(Mdns.GROUP), Mdns.PORT));
    }
}
