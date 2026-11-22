package cn.nastv.subtitles;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A bounded search for devices advertising SMB via DNS-SD. Call start and stop on the UI thread. */
final class LanDiscovery {
    private static final String SMB_TYPE = "_smb._tcp.";
    private static final long TIMEOUT_MS = 10_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NsdManager nsd;
    private final WifiManager wifi;
    private Search current;

    interface Listener {
        void onDevices(List<Device> devices);
        void onFinished(String reason);
    }

    static final class Device {
        public final String name;
        public final String host;
        public final int port;

        Device(String name, InetAddress address, int port) {
            this.name = name;
            this.host = address.getHostAddress();
            this.port = port;
        }

        String address() {
            String literal = host.contains(":") ? "[" + host + "]" : host;
            return port == 445 ? literal : literal + ":" + port;
        }
    }

    LanDiscovery(Context context) {
        Context app = context.getApplicationContext();
        nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
    }

    void start(Listener listener) {
        stop();
        if (nsd == null) {
            listener.onFinished("设备发现服务不可用，可手动输入 NAS 的局域网地址。");
            return;
        }
        current = new Search(listener);
        current.begin();
    }

    void stop() {
        if (current != null) current.end();
    }

    @SuppressWarnings("deprecation") // Fire OS 7 and 8 use the original discovery and resolve APIs.
    private final class Search implements NsdManager.DiscoveryListener {
        final Listener listener;
        final Map<String, NsdServiceInfo> present = new LinkedHashMap<>();
        final Map<String, Device> resolved = new LinkedHashMap<>();
        final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
        final Runnable timeout = () -> finish(null);
        final Runnable next = this::resolveNext;
        WifiManager.MulticastLock lock;
        boolean registered;
        boolean resolving;

        Search(Listener listener) { this.listener = listener; }

        void begin() {
            listener.onDevices(Collections.emptyList());
            if (current != this) return;
            if (wifi != null) {
                try {
                    lock = wifi.createMulticastLock("nastv:smb-discovery");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                } catch (RuntimeException unavailable) {
                    // An Ethernet-only device can still use NSD.
                    lock = null;
                }
            }
            try {
                nsd.discoverServices(SMB_TYPE, NsdManager.PROTOCOL_DNS_SD, this);
                registered = true;
                main.postDelayed(timeout, TIMEOUT_MS);
            } catch (RuntimeException unavailable) {
                finish("暂时无法发现设备，可重试或手动输入 NAS 的局域网地址。");
            }
        }

        void end() {
            current = null;
            main.removeCallbacks(timeout);
            main.removeCallbacks(next);
            if (registered) {
                try { nsd.stopServiceDiscovery(this); }
                catch (RuntimeException alreadyStopped) { /* Framework may have finished first. */ }
                registered = false;
            }
            if (lock != null && lock.isHeld()) lock.release();
        }

        void finish(String reason) {
            if (current != this) return;
            end();
            listener.onFinished(reason);
        }

        void emit() {
            List<Device> devices = new ArrayList<>(resolved.values());
            devices.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            listener.onDevices(Collections.unmodifiableList(devices));
        }

        String key(NsdServiceInfo service) {
            return service.getServiceName().toLowerCase(Locale.ROOT);
        }

        @Override public void onDiscoveryStarted(String type) {}

        @Override public void onStartDiscoveryFailed(String type, int error) {
            main.post(() -> {
                registered = false;
                finish("暂时无法发现设备，可重试或手动输入 NAS 的局域网地址。");
            });
        }

        @Override public void onDiscoveryStopped(String type) {
            main.post(() -> { registered = false; finish(null); });
        }

        @Override public void onStopDiscoveryFailed(String type, int error) {
            main.post(() -> finish("发现服务已中断，可重试或手动输入 NAS 的局域网地址。"));
        }

        @Override public void onServiceFound(NsdServiceInfo service) {
            main.post(() -> {
                if (current != this || service.getServiceName() == null) return;
                String key = key(service);
                if (present.containsKey(key)) return;
                present.put(key, service);
                pending.addLast(service);
                resolveNext();
            });
        }

        @Override public void onServiceLost(NsdServiceInfo service) {
            main.post(() -> {
                if (current != this || service.getServiceName() == null) return;
                String key = key(service);
                pending.remove(present.remove(key));
                if (resolved.remove(key) != null) emit();
            });
        }

        // Android 9-11 permits only one in-flight resolution per NsdManager.
        void resolveNext() {
            if (current != this || resolving || pending.isEmpty()) return;
            NsdServiceInfo original = pending.removeFirst();
            resolving = true;
            try {
                nsd.resolveService(original, new NsdManager.ResolveListener() {
                    @Override public void onServiceResolved(NsdServiceInfo result) {
                        main.post(() -> {
                            if (current != Search.this) return;
                            resolving = false;
                            InetAddress address = result.getHost();
                            if (present.get(key(original)) == original && address != null
                                    && result.getPort() > 0 && result.getPort() <= 65535) {
                                resolved.put(key(original), new Device(original.getServiceName(), address, result.getPort()));
                                emit();
                            }
                            resolveNext();
                        });
                    }

                    @Override public void onResolveFailed(NsdServiceInfo service, int error) {
                        main.post(() -> {
                            if (current != Search.this) return;
                            resolving = false;
                            if (error == NsdManager.FAILURE_ALREADY_ACTIVE && present.get(key(original)) == original) {
                                // A resolution from a recently closed picker can still be finishing.
                                pending.addLast(original);
                                main.postDelayed(next, 250);
                            } else {
                                resolveNext();
                            }
                        });
                    }
                });
            } catch (RuntimeException unavailable) {
                resolving = false;
                main.post(next);
            }
        }
    }
}
