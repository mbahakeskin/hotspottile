package com.mbaha.hotspottile;

import android.net.IIntResultListener;
import android.net.ITetheringConnector;
import android.net.ITetheringEventCallback;
import android.net.MacAddress;
import android.net.Network;
import android.net.TetherStatesParcel;
import android.net.TetheredClient;
import android.net.TetheringCallbackStartedParcel;
import android.net.TetheringConfigurationParcel;
import android.net.wifi.SoftApConfiguration;
import android.os.IBinder;
import android.util.SparseIntArray;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Telefonda "shell" (uid 2000) olarak, app_process ile calistirilir. Uygulamanin kendisi bu sinifi
 * calistirmaz; Bridge, yerel adbd uzerinden bu sinifi baslatir. Cikti: son satirda tek bir JSON.
 *
 * Komutlar: state | block MAC | unblock MAC | kick MAC | set k=v... | restart | power on|off
 */
public final class Agent {
    static final String PKG = "com.android.shell";
    static final int TETHERING_WIFI = 0;
    static final int AP_DISABLED = 11;
    static final int AP_ENABLED = 13;

    static Object wm;
    static ITetheringConnector tc;

    /** Panelde yerellestirilebilsin diye makine-okunur anahtar tasiyan hata (bkz. PanelActivity.errorText). */
    static final class AgentError extends RuntimeException {
        final String key;

        AgentError(String key) {
            super(key);
            this.key = key;
        }
    }

    /**
     * "serve" modunda stdin'den satir satir komut okur (alanlar TAB ile ayrilir) ve her satira tek satir JSON
     * yanitlar: panel acik kaldigi surece JVM bir kez baslar. Baska durumda tek komut calistirir.
     */
    public static void main(String[] a) throws Exception {
        try {
            init();
        } catch (Throwable t) {
            System.out.println("{\"ok\":false,\"error\":\"" + String.valueOf(t).replace("\"", "'") + "\"}");
            System.exit(1);
        }
        if (a.length > 0 && a[0].equals("daemon")) {
            daemon(a.length > 1 ? a[1] : "");
            System.exit(0);
        }
        if (a.length > 0 && a[0].equals("serve")) {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                System.out.println(handle(line.split("\t", -1)));
                System.out.flush();
            }
            System.exit(0);
        }
        System.out.println(handle(a));
        System.exit(0);
    }

    static JSONObject handle(String[] a) {
        JSONObject out = new JSONObject();
        try {
            String cmd = a.length > 0 ? a[0] : "state";
            switch (cmd) {
                case "state": break;
                case "block": setBlocked(a[1], true); break;
                case "unblock": setBlocked(a[1], false); break;
                case "kick": setBlocked(a[1], true); Thread.sleep(900); setBlocked(a[1], false); break;
                case "set": applySettings(a, out); break;
                case "restart": restart(); break;
                case "power": power(a[1].equals("on")); break;
                case "allow": setAllowed(a[1], true); break;
                case "disallow": setAllowed(a[1], false); break;
                case "grant": grant(a[1]); break;
                case "caps": caps(out); break;
                case "idle": setIdle(Integer.parseInt(a[1])); break;
                default: throw new AgentError("unknown_command");
            }
            fillState(out);
            out.put("ok", true);
        } catch (Throwable t) {
            Throwable c = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            try {
                out.put("ok", false);
                if (c instanceof AgentError) out.put("errorKey", ((AgentError) c).key);
                out.put("error", c.getClass().getSimpleName() + ": " + c.getMessage());
            } catch (Exception ignored) { }
        }
        return out;
    }

    // ---- altyapi ---------------------------------------------------------------------------------

    static void init() throws Exception {
        Method gs = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
        IBinder wifi = (IBinder) gs.invoke(null, "wifi");
        IBinder teth = (IBinder) gs.invoke(null, "tethering");
        wm = Class.forName("android.net.wifi.IWifiManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, wifi);
        tc = ITetheringConnector.Stub.asInterface(teth);
    }

    /** Ada ve parametre sayisina gore metod bulup cagirir (gizli/sistem API'leri icin). */
    static Object inv(Object o, String name, Object... args) throws Exception {
        for (Method m : o.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                try {
                    return m.invoke(o, args);
                } catch (IllegalArgumentException e) {
                    // ayni adli baska bir ust yukleme olabilir
                }
            }
        }
        throw new NoSuchMethodException(name + "/" + args.length);
    }

    static SoftApConfiguration cfg() throws Exception {
        return (SoftApConfiguration) inv(wm, "getSoftApConfiguration");
    }

    static Object builder(SoftApConfiguration c) throws Exception {
        return Class.forName("android.net.wifi.SoftApConfiguration$Builder")
                .getConstructor(SoftApConfiguration.class).newInstance(c);
    }

    static SoftApConfiguration build(Object b) throws Exception {
        return (SoftApConfiguration) inv(b, "build");
    }

    @SuppressWarnings("unchecked")
    static List<MacAddress> blocked(SoftApConfiguration c) throws Exception {
        return (List<MacAddress>) inv(c, "getBlockedClientList");
    }

    static void store(SoftApConfiguration next) throws Exception {
        if (!(Boolean) inv(wm, "validateSoftApConfiguration", next)) {
            throw new AgentError("invalid_config");
        }
        if (!(Boolean) inv(wm, "setSoftApConfiguration", next, PKG)) {
            throw new AgentError("save_failed");
        }
    }

    static int apState() throws Exception {
        return (Integer) inv(wm, "getWifiApEnabledState");
    }

    // ---- islemler --------------------------------------------------------------------------------

    static List<MacAddress> macList(String csv) {
        List<MacAddress> l = new ArrayList<>();
        for (String m : csv.split(",")) {
            if (!m.trim().isEmpty()) l.add(MacAddress.fromString(m.trim()));
        }
        return l;
    }

    @SuppressWarnings("unchecked")
    static List<MacAddress> allowed(SoftApConfiguration c) throws Exception {
        return (List<MacAddress>) inv(c, "getAllowedClientList");
    }

    /** Izinli liste (guvenilir cihazlar) calisan hotspot'ta anlik guncellenir; engelli listeden de cikarir. */
    static void setAllowed(String mac, boolean allow) throws Exception {
        SoftApConfiguration c = cfg();
        MacAddress m = MacAddress.fromString(mac);
        List<MacAddress> list = new ArrayList<>(allowed(c));
        if (allow && !list.contains(m)) list.add(m);
        if (!allow) list.remove(m);
        List<MacAddress> bl = new ArrayList<>(blocked(c));
        if (allow) bl.remove(m);
        Object b = builder(c);
        inv(b, "setAllowedClientList", list);
        inv(b, "setBlockedClientList", bl);
        store(build(b));
    }

    /**
     * Sistemin kendi "cihaz bagli degilse hotspot'u otomatik kapat" ayari. minutes: 0 = kapali, -1 = sistem
     * varsayilani (10 dk), N = N dakika. Yeniden baslatma gerektirmeden calisan hotspot'a uygulanir.
     */
    static void setIdle(int minutes) throws Exception {
        SoftApConfiguration c = cfg();
        Object b = builder(c);
        inv(b, "setAutoShutdownEnabled", minutes != 0);
        inv(b, "setShutdownTimeoutMillis", minutes > 0 ? minutes * 60_000L : -1L);
        store(build(b));
    }

    /**
     * Cihazin GERCEK hotspot yetenekleri (ulke kisitlari dahil): dumpsys wifi'deki SoftApCapability satirindan
     * kanal listeleri ve ulke kodu. Ornegin TR'de 6 GHz listesi bostur: yayin yapilamaz.
     */
    static void caps(JSONObject out) throws Exception {
        Process p = new ProcessBuilder("dumpsys", "wifi").redirectErrorStream(true).start();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf("SupportedChannelListIn24g");
                if (i < 0) continue;
                out.put("ch24", chList(line, "SupportedChannelListIn24g"));
                out.put("ch5", chList(line, "SupportedChannelListIn5g"));
                out.put("ch6", chList(line, "SupportedChannelListIn6g"));
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("MaximumSupportedClientNumber=(\\d+)").matcher(line);
                if (m.find()) out.put("maxSupported", Integer.parseInt(m.group(1)));
                m = java.util.regex.Pattern.compile("mCountryCodeFromDriver(\\w\\w)").matcher(line);
                if (m.find()) out.put("country", m.group(1));
                break;
            }
        } finally {
            p.destroy();
        }
    }

    private static JSONArray chList(String line, String key) {
        JSONArray a = new JSONArray();
        int i = line.indexOf(key + "[");
        if (i < 0) return a;
        int j = line.indexOf(']', i);
        for (String t : line.substring(i + key.length() + 1, j).split(",")) {
            t = t.trim();
            if (!t.isEmpty()) a.put(Integer.parseInt(t));
        }
        return a;
    }

    // ---- daemon: uygulama kapaliyken bile olay yakalar ---------------------------------------------

    static final String LOCK = "/data/local/tmp/hotspottile.lock";
    static final String PIDFILE = "/data/local/tmp/hotspottile.pid";

    static boolean daemonAlive() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(PIDFILE))) {
            String pid = r.readLine();
            try (java.io.BufferedReader c = new java.io.BufferedReader(new java.io.FileReader("/proc/" + pid.trim() + "/cmdline"))) {
                String cmd = c.readLine();
                return cmd != null && cmd.contains("Agent") && cmd.contains("daemon");
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static final java.util.concurrent.ExecutorService BCAST = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static String lastClientsJson = "", daemonToken = "";
    private static int lastAp = -1;

    /** Olayi uygulamanin EventReceiver'ina "am broadcast" ile iletir (uygulama calismiyorsa sistem baslatir). */
    private static void sendEvent(String event, String data, boolean snapshot) {
        BCAST.execute(() -> {
            try {
                Process p = new ProcessBuilder("am", "broadcast", "--user", "0",
                        "-n", "com.mbaha.hotspottile/.EventReceiver", "-a", "com.mbaha.hotspottile.EVENT",
                        "--es", "token", daemonToken, "--es", "event", event, "--es", "data", data,
                        "--ez", "snapshot", snapshot ? "true" : "false")
                        .redirectErrorStream(true).start();
                java.io.InputStream in = p.getInputStream();
                while (in.read() >= 0) { /* ciktiyi bosalt */ }
                p.waitFor();
            } catch (Exception ignored) { }
        });
    }

    private static void clientsEvent(List<TetheredClient> l, boolean snapshot) {
        try {
            String json = clientsJson(l).toString();
            if (!snapshot && json.equals(lastClientsJson)) return; // degisim yok
            lastClientsJson = json;
            sendEvent("clients", json, snapshot);
        } catch (Exception ignored) { }
    }

    private static void apEvent() {
        try {
            int st = apState();
            if (st == lastAp) return;
            lastAp = st;
            sendEvent("ap", String.valueOf(st), false);
        } catch (Exception ignored) { }
    }

    /**
     * Tek kopya (dosya kilidi) calisan, sistemin tethering olaylarina abone kalan surec. Yoklama yok: istemci
     * listesi ve hotspot durumu degisince uyanir, yalniz o zaman EventReceiver'i tetikler. Telefon yeniden
     * baslayinca ya da adbd yeniden baslayinca olur; uygulama tarafi gerektiginde yeniden baslatir.
     */
    static void daemon(String token) throws Exception {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(LOCK, "rw");
        java.nio.channels.FileLock lock = raf.getChannel().tryLock();
        if (lock == null) return;                       // zaten calisiyor
        daemonToken = token;
        try (java.io.FileWriter w = new java.io.FileWriter(PIDFILE)) {
            w.write(String.valueOf(android.os.Process.myPid()));
        }
        ITetheringEventCallback cb = new ITetheringEventCallback.Stub() {
            @Override public void onCallbackStarted(TetheringCallbackStartedParcel p) {
                clientsEvent(p.tetheredClients, true);
                apEvent();
            }
            @Override public void onTetherClientsChanged(List<TetheredClient> l) { clientsEvent(l, false); }
            @Override public void onTetherStatesChanged(TetherStatesParcel s) { apEvent(); }
            @Override public void onCallbackStopped(int e) { }
            @Override public void onUpstreamChanged(Network n) { }
            @Override public void onConfigurationChanged(TetheringConfigurationParcel c) { }
            @Override public void onOffloadStatusChanged(int s) { }
            @Override public void onSupportedTetheringTypes(long b) { }
        };
        tc.registerTetheringEventCallback(cb, PKG);
        Object forever = new Object();
        synchronized (forever) {
            forever.wait();
        }
    }

    /** Uygulamaya calisma zamani izni verir (orn. bildirim): shell'in "pm grant" yetkisiyle. */
    static void grant(String permission) throws Exception {
        Process p = new ProcessBuilder("pm", "grant", "com.mbaha.hotspottile", permission).redirectErrorStream(true).start();
        p.waitFor(5, TimeUnit.SECONDS);
    }

    /** Hotspot arayuzunun (ap_br_*, ap*, swlan*, softap*) bayt sayaclari: istemcilerin gozunden indirilen = tx. */
    static void fillUsage(JSONObject out) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/net/dev"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String name = line.substring(0, colon).trim();
                if (!(name.startsWith("ap_br_") || name.matches("(ap|swlan|softap)\\d*"))) continue;
                String[] f = line.substring(colon + 1).trim().split("\\s+");
                out.put("usageUp", Long.parseLong(f[0]));      // istemcilerden gelen (yukleme)
                out.put("usageDown", Long.parseLong(f[8]));    // istemcilere giden (indirme)
                return;
            }
        } catch (Exception ignored) { }
    }

    /** Engelleme listesi calisan hotspot'a ANINDA uygulanir ve bagli cihazi dusurur (yeniden baslatma yok). */
    static void setBlocked(String mac, boolean block) throws Exception {
        SoftApConfiguration c = cfg();
        MacAddress m = MacAddress.fromString(mac);
        List<MacAddress> list = new ArrayList<>(blocked(c));
        if (block && !list.contains(m)) list.add(m);
        if (!block) list.remove(m);
        Object b = builder(c);
        inv(b, "setBlockedClientList", list);
        store(build(b));
    }

    /** Anahtar=deger ayarlari. Ad/sifre/bant/gizli ancak hotspot yeniden baslayinca gecerli olur. */
    static void applySettings(String[] a, JSONObject out) throws Exception {
        Map<String, String> kv = new HashMap<>();
        for (int i = 1; i < a.length; i++) {
            int eq = a[i].indexOf('=');
            if (eq > 0) kv.put(a[i].substring(0, eq), a[i].substring(eq + 1));
        }
        SoftApConfiguration c = cfg();
        Object b = builder(c);

        if (kv.containsKey("ssid")) inv(b, "setSsid", kv.get("ssid"));
        if (kv.containsKey("security") || kv.containsKey("pass")) {
            int sec = c.getSecurityType();
            if (kv.containsKey("security")) {
                switch (kv.get("security")) {
                    case "wpa3": sec = 3; break;          // SECURITY_TYPE_WPA3_SAE
                    case "wpa2wpa3": sec = 2; break;      // SECURITY_TYPE_WPA3_SAE_TRANSITION
                    default: sec = 1;                     // SECURITY_TYPE_WPA2_PSK
                }
            } else if (sec == 0) {
                sec = 1; // acik agdan sifreliye gecerken WPA2
            }
            String pass = kv.containsKey("pass") ? kv.get("pass") : c.getPassphrase();
            inv(b, "setPassphrase", pass, sec);
        }
        if (kv.containsKey("hidden")) inv(b, "setHiddenSsid", kv.get("hidden").equals("1"));
        if (kv.containsKey("maxclients")) inv(b, "setMaxNumberOfClients", Integer.parseInt(kv.get("maxclients")));
        if (kv.containsKey("bands")) {
            int channel = kv.containsKey("channel") ? Integer.parseInt(kv.get("channel")) : 0;
            SparseIntArray ch = new SparseIntArray();
            switch (kv.get("bands")) {
                case "2": ch.put(1, channel); break;    // 2,4 GHz
                case "5": ch.put(2, channel); break;    // 5 GHz
                case "6": ch.put(4, 0); break;          // 6 GHz (kanal otomatik)
                default: ch.put(1, 0); ch.put(3, 0);    // cift bant (bridged): Pixel'in "2,4 ve 5 GHz" secimi tam olarak {1=0, 3=0} yazar
            }
            inv(b, "setChannels", ch);
        } else if (kv.containsKey("channel")) {
            SparseIntArray cur = c.getChannels();
            if (cur.size() == 1) {
                SparseIntArray ch = new SparseIntArray();
                ch.put(cur.keyAt(0), Integer.parseInt(kv.get("channel")));
                inv(b, "setChannels", ch);
            }
        }
        if (kv.containsKey("ax")) inv(b, "setIeee80211axEnabled", kv.get("ax").equals("1"));
        if (kv.containsKey("be")) inv(b, "setIeee80211beEnabled", kv.get("be").equals("1"));
        if (kv.containsKey("macrand")) inv(b, "setMacRandomizationSetting", Integer.parseInt(kv.get("macrand")));
        if (kv.containsKey("clientcontrol")) inv(b, "setClientControlByUserEnabled", kv.get("clientcontrol").equals("1"));
        if (kv.containsKey("allowed")) inv(b, "setAllowedClientList", macList(kv.get("allowed")));
        store(build(b));
        out.put("restartNeeded", true);
    }

    static IIntResultListener listener() {
        return new IIntResultListener.Stub() {
            @Override public void onResult(int r) { }
        };
    }

    static void waitState(int want, long timeoutMs) throws Exception {
        long t0 = System.currentTimeMillis();
        while (apState() != want && System.currentTimeMillis() - t0 < timeoutMs) Thread.sleep(200);
    }

    static void stopAp() throws Exception {
        tc.stopTethering(TETHERING_WIFI, PKG, null, listener());
        waitState(AP_DISABLED, 15000);
    }

    static void startAp() throws Exception {
        Class<?> bc = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
        Object builder = bc.getConstructor(int.class).newInstance(TETHERING_WIFI);
        Object req = bc.getMethod("build").invoke(builder);
        req.getClass().getMethod("setPackageName", String.class).invoke(req, PKG);
        req.getClass().getMethod("setUid", int.class).invoke(req, 2000);
        Object parcel = req.getClass().getMethod("getParcel").invoke(req);
        Method start = null;
        for (Method m : ITetheringConnector.class.getMethods()) {
            if (m.getName().equals("startTethering")) start = m;
        }
        start.invoke(tc, parcel, PKG, null, listener());
        waitState(AP_ENABLED, 20000);
    }

    static void restart() throws Exception {
        stopAp();
        startAp();
    }

    static void power(boolean on) throws Exception {
        if (on && apState() != AP_ENABLED) startAp();
        if (!on && apState() != AP_DISABLED) stopAp();
    }

    // ---- durum -----------------------------------------------------------------------------------

    static void fillState(JSONObject out) throws Exception {
        SoftApConfiguration c = cfg();
        out.put("ssid", c.getSsid());
        out.put("hidden", c.isHiddenSsid());
        out.put("security", c.getSecurityType());
        out.put("maxClients", inv(c, "getMaxNumberOfClients"));
        out.put("apState", apState());
        out.put("pass", c.getPassphrase());
        try {
            out.put("supports6", (Boolean) inv(wm, "is6GHzBandSupported"));
        } catch (Throwable t) {
            out.put("supports6", false);
        }
        out.put("clientControl", (Boolean) inv(c, "isClientControlByUserEnabled"));
        out.put("autoShutdown", (Boolean) inv(c, "isAutoShutdownEnabled"));
        out.put("shutdownMs", (Long) inv(c, "getShutdownTimeoutMillis"));
        out.put("ax", (Boolean) inv(c, "isIeee80211axEnabled"));
        out.put("be", (Boolean) inv(c, "isIeee80211beEnabled"));
        out.put("macRand", (Integer) inv(c, "getMacRandomizationSetting"));
        out.put("daemon", daemonAlive());
        try {
            out.put("country", String.valueOf(inv(wm, "getCountryCode", PKG, null)));
        } catch (Throwable ignored) { }
        JSONArray al = new JSONArray();
        for (MacAddress m : allowed(c)) al.put(m.toString());
        out.put("allowed", al);
        fillUsage(out);

        SparseIntArray ch = c.getChannels();
        String mode;
        if (ch.size() >= 2) mode = "dual";
        else if (ch.size() == 1) mode = ch.keyAt(0) == 1 ? "2" : ch.keyAt(0) == 2 ? "5" : ch.keyAt(0) == 4 ? "6" : "auto";
        else mode = "auto";
        out.put("bands", mode);
        out.put("channel", ch.size() == 1 ? ch.valueAt(0) : 0);

        JSONArray bl = new JSONArray();
        for (MacAddress m : blocked(c)) bl.put(m.toString());
        out.put("blocked", bl);

        out.put("clients", clients());
    }

    /**
     * Yalnizca gercek Wi-Fi hotspot istemcileri: TetheredClient listesi USB/Bluetooth tethering'e bagli
     * cihazlari da icerir (orn. bu telefona USB ile bagli bilgisayar). "Hotspot'uma kim bagli" sorusunun
     * kapsami disinda kalir, sistemin kendi Hotspot ekranindaki sayimla da eslesmez - disarida birakilir.
     */
    static JSONArray clientsJson(List<TetheredClient> list) throws Exception {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        for (TetheredClient c : list) {
            if (c.getTetheringType() != TETHERING_WIFI) continue;
            List<TetheredClient.AddressInfo> addrs = c.getAddresses();
            String ip = null, host = null;
            if (addrs != null && !addrs.isEmpty()) {
                ip = addrs.get(0).getAddress().getAddress().getHostAddress();
                host = addrs.get(0).getHostname();
            }
            JSONObject o = new JSONObject();
            o.put("mac", c.getMacAddress().toString());
            o.put("ip", ip);
            o.put("host", host);
            o.put("type", c.getTetheringType());
            arr.put(o);
        }
        return arr;
    }

    static JSONArray clients() throws Exception {
        final JSONArray arr = new JSONArray();
        final CountDownLatch done = new CountDownLatch(1);
        ITetheringEventCallback cb = new ITetheringEventCallback.Stub() {
            @Override public void onCallbackStarted(TetheringCallbackStartedParcel p) {
                try {
                    JSONArray l = clientsJson(p.tetheredClients);
                    for (int i = 0; i < l.length(); i++) arr.put(l.get(i));
                } catch (Exception ignored) { }
                done.countDown();
            }
            @Override public void onCallbackStopped(int e) { }
            @Override public void onUpstreamChanged(Network n) { }
            @Override public void onConfigurationChanged(TetheringConfigurationParcel c) { }
            @Override public void onTetherStatesChanged(TetherStatesParcel s) { }
            @Override public void onTetherClientsChanged(List<TetheredClient> l) { }
            @Override public void onOffloadStatusChanged(int s) { }
            @Override public void onSupportedTetheringTypes(long b) { }
        };
        tc.registerTetheringEventCallback(cb, PKG);
        done.await(3, TimeUnit.SECONDS);
        tc.unregisterTetheringEventCallback(cb, PKG);
        return arr;
    }
}
