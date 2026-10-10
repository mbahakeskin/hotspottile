package com.mbaha.hotspottile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Hotspot paneli (tile'a uzun basinca da acilir). XML layout yok: View agacini kodla kurmak tek ekranlik
 * dinamik bir panel icin daha az ek yuk getirir. Tum ayricalikli islemler {@link Bridge} uzerinden arka
 * plan is parcacigindan cagirilir; UI hic bloklanmaz.
 *
 * Gorsel dil Android 16/17 Ayarlar'in Material 3 Expressive duzenidir (bkz. Ui.java). Ag adi / sifre /
 * sinir / guvenlik / kanal satirlari dokununca acilan diyaloglarla duzenlenir; degisiklikler "bekleyen"
 * tutulur ve alttaki cubukta "Uygula" ile toplu uygulanir (hotspot yeniden baslar). Otomasyon ve
 * cihaz adi/guven ayarlari yeniden baslatma gerektirmez, aninda gecerlidir.
 */
public class PanelActivity extends Activity {
    private static final long POLL_MS = 4000;
    private static final int[] LIMITS = {0, 1, 2, 3, 4, 5, 6, 8, 10, 16, 32};
    private static final int[] IDLE_MIN = {0, 5, 10, 15, 30, 60};
    private static final int[] TIMER_MIN = {0, 30, 60, 120, 240, 480};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout applyBar;
    private TextView applyCancel, applyConfirm;

    private boolean resumed;
    private JSONObject caps;            // cihaz yetenekleri (kanal listeleri, ulke); bos olabilir
    private JSONObject state;
    private String pendingSsid, pendingPass, pendingSecurity, pendingBands;
    private Boolean pendingHidden, pendingAx, pendingBe;
    private Integer pendingMacRand;
    private Integer pendingMaxClients, pendingChannel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildSkeleton();
        NetReceiver.register(this);   // Wi-Fi olay kaydi (idempotent)
        askNotificationPermission();
        askAddTile();
    }

    /** Tile henuz eklenmediyse sistemin kendi "Tile ekle?" onay penceresini bir kez gosterir. */
    private void askAddTile() {
        Store st = Store.get(this);
        if (st.flag("askedTile")) return;
        st.setFlag("askedTile");
        try {
            getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                    new android.content.ComponentName(this, HotspotTileService.class),
                    getString(R.string.app_name),
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_hotspot),
                    getMainExecutor(), result -> { });
        } catch (Exception ignored) { }
    }

    private void askNotificationPermission() {
        Store st = Store.get(this);
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                || st.flag("askedNotif")) return;
        st.setFlag("askedNotif");
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override
    protected void onStart() {
        super.onStart();
        resumed = true;
        refresh(state == null);
        main.postDelayed(poll, POLL_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        resumed = false;
        main.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---- periyodik yenileme -------------------------------------------------------------------

    /** Panel acikken birkac saniyede bir sessizce yeniler; bekleyen degisiklik varsa ekrani bozmaz. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            io.execute(() -> {
                JSONObject s = null;
                try {
                    s = Bridge.call(PanelActivity.this, "state");
                } catch (Exception ignored) {
                    // sessiz gec: kisa bir kesinti kullaniciyi rahatsiz etmesin
                }
                JSONObject fs = s;
                main.post(() -> {
                    if (!resumed) return;
                    if (fs != null && !hasPendingChanges()) {
                        setState(fs);
                        render();
                    }
                    main.postDelayed(poll, POLL_MS);
                });
            });
        }
    };

    private void setState(JSONObject s) {
        state = s;
        // Bildirim/otomasyon ozelligi aciksa ve telefondaki olay sureci calismiyorsa baslat; yetenekleri onbellekten al.
        io.execute(() -> {
            Daemon.ensure(this, s);
            loadCaps(s.optString("country", null));
        });
    }

    /** Ulke kisitlarini iceren gercek kanal listelerini bir kez okur ve 24 saat onbellekte tutar. */
    private void loadCaps(String countryNow) {
        Store st = Store.get(this);
        try {
            String cachedCountry = st.capsJson() == null ? null : new JSONObject(st.capsJson()).optString("country", null);
            // Ulke kodu degistiyse (orn. yolculuk, elle degisiklik) kanal listeleri de degisir: onbellegi atla.
            boolean countryChanged = countryNow != null && cachedCountry != null && !countryNow.equalsIgnoreCase(cachedCountry);
            if (st.capsJson() == null || countryChanged || System.currentTimeMillis() - st.capsAt() > 24 * 3600_000L) {
                JSONObject r = Bridge.call(this, "caps");
                if (r.has("ch24")) {
                    st.setCaps(r.toString());
                    main.post(() -> { caps = r; if (state != null && !hasPendingChanges()) render(); });
                    return;
                }
            }
            if (caps == null && st.capsJson() != null) {
                JSONObject c = new JSONObject(st.capsJson());
                main.post(() -> { caps = c; if (state != null && !hasPendingChanges()) render(); });
            }
        } catch (Exception ignored) { }
    }

    private int[] chList(String key, int[] fallback) {
        JSONArray a = caps != null ? caps.optJSONArray(key) : null;
        if (a == null || a.length() == 0) return fallback;
        int[] out = new int[a.length() + 1];            // 0 = otomatik
        for (int i = 0; i < a.length(); i++) out[i + 1] = a.optInt(i);
        return out;
    }

    // ---- iskelet -----------------------------------------------------------------------------

    private void buildSkeleton() {
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        root = Ui.column(this);
        root.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 32));
        scroll.addView(root);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.role(this, "surface_container"));
        frame.addView(scroll);

        applyBar = buildApplyBar();
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        barLp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        frame.addView(applyBar, barLp);

        setContentView(frame);

        // Cubuk renkleri seffaf; simge tonunu (acik temada koyu simgeler) temaya gore biz ayarlariz.
        int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(Ui.night(this) ? 0 : lightBars, lightBars);

        // Android 15+ icerigi cubuklarin ARKASINA cizer (edge-to-edge zorunlu): kenar bosluklari bizde.
        int barGap = Ui.dp(this, 12);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            scroll.setPadding(0, bars.top, 0, bars.bottom);
            barLp.bottomMargin = barGap + bars.bottom;
            applyBar.setLayoutParams(barLp);
            return insets;
        });

        renderLoading();
    }

    private LinearLayout buildApplyBar() {
        LinearLayout bar = Ui.column(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.role(this, "surface_container_highest"));
        bg.setCornerRadius(Ui.dp(this, 28));
        bar.setBackground(bg);
        bar.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 12));
        bar.setElevation(Ui.dp(this, 6));
        bar.setVisibility(View.GONE);

        TextView warn = Ui.text(this, getString(R.string.apply_warning), 13, "on_surface_variant", false);
        warn.setPadding(0, 0, Ui.dp(this, 8), Ui.dp(this, 8));
        bar.addView(warn);

        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView cancel = Ui.textButton(this, getString(R.string.cancel));
        cancel.setOnClickListener(v -> { clearPending(); render(); });
        row.addView(cancel);
        TextView apply = Ui.filledButton(this, getString(R.string.apply));
        apply.setOnClickListener(v -> applyPending());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        row.addView(apply, lp);
        bar.addView(row);

        applyCancel = cancel;
        applyConfirm = apply;
        return bar;
    }

    private void header() {
        root.addView(Ui.topBar(this, getString(R.string.app_name), v -> finish()));
    }

    private void renderLoading() {
        root.removeAllViews();
        header();
        TextView hint = Ui.text(this, getString(R.string.loading_hint), 14, "on_surface_variant", false);
        hint.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        root.addView(hint);
    }

    private void renderError(String message) {
        root.removeAllViews();
        header();
        TextView err = Ui.text(this, message, 16, "on_surface", false);
        err.setTextColor(Ui.error(this));
        err.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 16));
        root.addView(err);
        TextView retry = Ui.filledButton(this, getString(R.string.retry));
        retry.setOnClickListener(v -> refresh(true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 16);
        root.addView(retry, lp);
    }

    // ---- veri akisi ----------------------------------------------------------------------------

    private void refresh(boolean showLoadingFirst) {
        if (showLoadingFirst) renderLoading();
        io.execute(() -> {
            try {
                JSONObject s = Bridge.call(this, "state");
                main.post(() -> { setState(s); clearPending(); render(); });
            } catch (Exception e) {
                main.post(() -> renderError(describe(e)));
            }
        });
    }

    private String describe(Exception e) {
        String m = e.getMessage();
        return m != null ? m : e.getClass().getSimpleName();
    }

    private String errorText(JSONObject r) {
        switch (r.optString("errorKey")) {
            case "invalid_config": return getString(R.string.err_invalid_config);
            case "save_failed": return getString(R.string.err_save_failed);
            case "unknown_command": return getString(R.string.err_unknown_command);
            default: return r.optString("error", getString(R.string.action_failed));
        }
    }

    /** Anlik islemler (engelle/dusur/guc): perde yok, sadece sonucu uygular. */
    private void act(String... args) {
        io.execute(() -> {
            try {
                JSONObject r = Bridge.call(this, args);
                main.post(() -> {
                    if (!r.optBoolean("ok", false)) {
                        Toast.makeText(this, errorText(r), Toast.LENGTH_LONG).show();
                    }
                    if (r.optBoolean("ok", false) && args.length > 1) {
                        if ("block".equals(args[0])) Store.get(this).setBlockedAt(args[1], true);
                        else if ("unblock".equals(args[0])) Store.get(this).setBlockedAt(args[1], false);
                    }
                    if (args.length > 1 && "power".equals(args[0]) && "on".equals(args[1])) {
                        Store.get(this).setApOnAt(System.currentTimeMillis());
                    }
                    setState(r);
                    clearPending();
                    render();
                });
            } catch (Exception e) {
                main.post(() -> {
                    Toast.makeText(this, describe(e), Toast.LENGTH_LONG).show();
                    if (state != null) render(); // anahtar gorunumunu gercek duruma geri al
                });
            }
        });
    }

    private void clearPending() {
        pendingSsid = pendingPass = pendingBands = pendingSecurity = null;
        pendingHidden = null;
        pendingMaxClients = pendingChannel = null;
        pendingAx = pendingBe = null;
        pendingMacRand = null;
    }

    private boolean hasPendingChanges() {
        return pendingSsid != null || !TextUtils.isEmpty(pendingPass) || pendingHidden != null
                || pendingMaxClients != null || pendingBands != null || pendingSecurity != null
                || pendingChannel != null || pendingAx != null || pendingBe != null || pendingMacRand != null;
    }

    private void syncApplyBar() {
        boolean show = hasPendingChanges();
        applyBar.setVisibility(show ? View.VISIBLE : View.GONE);
        root.setPadding(root.getPaddingLeft(), 0, root.getPaddingRight(), Ui.dp(this, show ? 170 : 32));
    }

    private void setApplyBusy(boolean busy) {
        applyCancel.setEnabled(!busy);
        applyConfirm.setEnabled(!busy);
        applyCancel.setAlpha(busy ? 0.4f : 1f);
        applyConfirm.setText(busy ? R.string.applying : R.string.apply);
        applyConfirm.setAlpha(busy ? 0.7f : 1f);
    }

    private void applyPending() {
        List<String> args = new ArrayList<>();
        args.add("set");
        if (pendingSsid != null) args.add("ssid=" + pendingSsid);
        if (!TextUtils.isEmpty(pendingPass)) args.add("pass=" + pendingPass);
        if (pendingSecurity != null) args.add("security=" + pendingSecurity);
        if (pendingHidden != null) args.add("hidden=" + (pendingHidden ? "1" : "0"));
        if (pendingMaxClients != null) args.add("maxclients=" + pendingMaxClients);
        if (pendingBands != null) args.add("bands=" + pendingBands);
        if (pendingChannel != null) args.add("channel=" + pendingChannel);
        if (pendingAx != null) args.add("ax=" + (pendingAx ? "1" : "0"));
        if (pendingBe != null) args.add("be=" + (pendingBe ? "1" : "0"));
        if (pendingMacRand != null) args.add("macrand=" + pendingMacRand);
        setApplyBusy(true);
        io.execute(() -> {
            try {
                JSONObject set = Bridge.call(this, args.toArray(new String[0]));
                if (!set.optBoolean("ok", false)) {
                    main.post(() -> {
                        setApplyBusy(false);
                        Toast.makeText(this, errorText(set), Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                Bridge.call(this, "restart");
                JSONObject s = Bridge.call(this, "state");
                main.post(() -> { setApplyBusy(false); setState(s); clearPending(); render(); });
            } catch (Exception e) {
                main.post(() -> { setApplyBusy(false); Toast.makeText(this, describe(e), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    // ---- bicimleme -------------------------------------------------------------------------------

    private String devicesText(int n) {
        return getResources().getQuantityString(R.plurals.devices_count, n, n);
    }

    private String fmtMin(long min) {
        if (min < 1) return getString(R.string.dur_lt_min);
        if (min < 60) return getString(R.string.dur_min, (int) min);
        long h = min / 60, m = min % 60;
        return m == 0 ? getString(R.string.dur_hour, (int) h) : getString(R.string.dur_hour_min, (int) h, (int) m);
    }

    private String minLabel(int min) {
        return min == 0 ? getString(R.string.off_label) : fmtMin(min);
    }

    private static String secKey(int sec) {
        switch (sec) {
            case 0: return "open";
            case 2: return "wpa2wpa3";
            case 3: return "wpa3";
            default: return "wpa2";
        }
    }

    private String secLabel(String key) {
        switch (key) {
            case "open": return getString(R.string.sec_open);
            case "wpa2wpa3": return getString(R.string.sec_wpa2wpa3);
            case "wpa3": return getString(R.string.sec_wpa3);
            default: return getString(R.string.sec_wpa2);
        }
    }

    private String limitText(int v) {
        return v == 0 ? getString(R.string.unlimited) : String.valueOf(v);
    }

    // ---- gorunum -----------------------------------------------------------------------------------

    private void render() {
        root.removeAllViews();
        header();
        boolean on = state.optInt("apState") == 13;

        JSONArray clients = state.optJSONArray("clients");
        int n = clients != null ? clients.length() : 0;
        String sub = !on ? getString(R.string.status_off)
                : n == 0 ? getString(R.string.status_on_none)
                : getString(R.string.status_on_fmt, devicesText(n));

        Ui.M3Switch power = new Ui.M3Switch(this);
        power.setChecked(on);
        power.setOnToggle(checked -> act("power", checked ? "on" : "off"));
        LinearLayout master = Ui.item(this, getString(R.string.master_title), sub, power);
        master.setOnClickListener(v -> power.toggle());
        root.addView(Ui.group(this, master));

        syncApplyBar();
        if (on) {
            renderNetworkCard();
            renderBandsCard();
            renderClientsCard(clients);
            renderUsageCard();
        }
        if (on) renderRadioCard();
        renderAutomationCard(on);
        renderAdvancedCard();
    }

    private LinearLayout switchRow(String title, String summary, boolean checked, Consumer<Boolean> onToggle) {
        Ui.M3Switch sw = new Ui.M3Switch(this);
        sw.setChecked(checked);
        sw.setOnToggle(onToggle);
        LinearLayout row = Ui.item(this, title, summary, sw);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    // -- ag --

    private void renderNetworkCard() {
        root.addView(Ui.sectionLabel(this, getString(R.string.section_network)));

        String ssidNow = pendingSsid != null ? pendingSsid : state.optString("ssid");
        LinearLayout ssidRow = Ui.item(this, getString(R.string.network_name), ssidNow, Ui.qrButton(this, v -> showQr()));
        ssidRow.setOnClickListener(v -> editText(R.string.network_name, ssidNow, false, null, value -> {
            if (value.isEmpty()) return getString(R.string.err_ssid_empty);
            if (value.getBytes(StandardCharsets.UTF_8).length > 32) return getString(R.string.err_ssid_long);
            return null;
        }, value -> {
            pendingSsid = value.equals(state.optString("ssid")) ? null : value;
            render();
        }));

        boolean passPending = !TextUtils.isEmpty(pendingPass);
        String currentPass = passPending ? pendingPass : state.optString("pass", "");
        int baseSec = state.optInt("security");
        boolean open = baseSec == 0 && pendingSecurity == null;
        LinearLayout passRow = Ui.item(this, getString(R.string.password),
                getString(passPending ? R.string.password_summary_pending : R.string.password_summary), null);
        passRow.setOnClickListener(v -> editText(R.string.password, currentPass, true,
                getString(R.string.password_copy), value -> {
            if (value.length() < 8 || value.length() > 63) return getString(R.string.err_pass_short);
            return null;
        }, value -> {
            pendingPass = value.equals(state.optString("pass", "")) ? null : value;
            render();
        }));

        String secNow = pendingSecurity != null ? pendingSecurity : secKey(baseSec);
        LinearLayout secRow = Ui.item(this, getString(R.string.security), secLabel(secNow), null);
        secRow.setOnClickListener(v -> {
            String[] keys = {"wpa2", "wpa2wpa3", "wpa3"};
            String[] labels = {secLabel("wpa2"), secLabel("wpa2wpa3"), secLabel("wpa3")};
            int checked = 0;
            for (int i = 0; i < keys.length; i++) if (keys[i].equals(secNow)) checked = i;
            pick(R.string.security, labels, checked, which -> {
                pendingSecurity = keys[which].equals(secKey(baseSec)) ? null : keys[which];
                render();
            });
        });

        boolean hiddenNow = pendingHidden != null ? pendingHidden : state.optBoolean("hidden");
        LinearLayout hiddenRow = switchRow(getString(R.string.hide_network), getString(R.string.hide_network_summary),
                hiddenNow, checked -> {
                    pendingHidden = checked == state.optBoolean("hidden") ? null : checked;
                    syncApplyBar();
                });

        int baseMax = state.optInt("maxClients");
        int curMax = pendingMaxClients != null ? pendingMaxClients : baseMax;
        LinearLayout maxRow = Ui.item(this, getString(R.string.device_limit), limitText(curMax), null);
        maxRow.setOnClickListener(v -> {
            String[] labels = new String[LIMITS.length];
            int checked = 0;
            for (int i = 0; i < LIMITS.length; i++) {
                labels[i] = limitText(LIMITS[i]);
                if (LIMITS[i] == curMax) checked = i;
            }
            pick(R.string.device_limit, labels, checked, which -> {
                pendingMaxClients = LIMITS[which] == baseMax ? null : LIMITS[which];
                render();
            });
        });

        List<View> rows = new ArrayList<>();
        rows.add(ssidRow);
        if (!open) rows.add(passRow);
        rows.add(secRow);
        rows.add(hiddenRow);
        rows.add(maxRow);
        root.addView(Ui.group(this, rows.toArray(new View[0])));
    }

    /** Sistemdeki gibi tam sayfa "Hotspot'u paylas" (parmak izi dogrulamali, animasyonlu QR). */
    private void showQr() {
        startActivity(new android.content.Intent(this, ShareActivity.class)
                .putExtra("ssid", state.optString("ssid"))
                .putExtra("pass", state.optString("pass", ""))
                .putExtra("sec", state.optInt("security"))
                .putExtra("hidden", state.optBoolean("hidden")));
    }

    // -- bant / kanal --

    private void renderBandsCard() {
        root.addView(Ui.sectionLabel(this, getString(R.string.section_band)));
        String base = state.optString("bands", "dual");
        String cur = pendingBands != null ? pendingBands : base;
        // 6 GHz yalniz sistem bu ulkede o bantta kanal bildiriyorsa (TR'de bos): aksi halde istek sessizce 5 GHz'e duser.
        JSONArray c6 = caps != null ? caps.optJSONArray("ch6") : null;
        boolean has6 = state.optBoolean("supports6") && c6 != null && c6.length() > 0;
        List<String> labels = new ArrayList<>(), values = new ArrayList<>();
        labels.add(getString(R.string.band_24)); values.add("2");
        labels.add(getString(R.string.band_5)); values.add("5");
        if (has6 || "6".equals(base)) { labels.add(getString(R.string.band_6)); values.add("6"); }
        labels.add(getString(R.string.band_dual)); values.add("dual");
        LinearLayout seg = Ui.segmented(this, labels.toArray(new String[0]), values.toArray(new String[0]), cur, value -> {
            pendingBands = value.equals(base) ? null : value;
            pendingChannel = null;       // bant degisince kanal otomatige doner
            render();
        });
        LinearLayout holder = Ui.column(this);
        holder.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        holder.addView(seg);

        List<View> rows = new ArrayList<>();
        rows.add(holder);
        if ("2".equals(cur) || "5".equals(cur)) {
            int[] list = "2".equals(cur) ? chList("ch24", new int[]{0, 1, 6, 11}) : chList("ch5", new int[]{0, 36, 40, 44, 48});
            int baseCh = state.optInt("channel");
            boolean bandChanged = pendingBands != null;
            int curCh = pendingChannel != null ? pendingChannel : bandChanged ? 0 : baseCh;
            LinearLayout chRow = Ui.item(this, getString(R.string.channel), chLabel(curCh), null);
            chRow.setOnClickListener(v -> {
                String[] lbl = new String[list.length];
                int checked = 0;
                for (int i = 0; i < list.length; i++) {
                    lbl[i] = chLabel(list[i]);
                    if (list[i] == curCh) checked = i;
                }
                pick(R.string.channel, lbl, checked, which -> {
                    int ch = list[which];
                    pendingChannel = (!bandChanged && ch == baseCh) ? null : ch;
                    if (bandChanged && ch == 0) pendingChannel = null;
                    render();
                });
            });
            rows.add(chRow);
        }
        root.addView(Ui.group(this, rows.toArray(new View[0])));
    }

    private String chLabel(int ch) {
        return ch == 0 ? getString(R.string.channel_auto) : getString(R.string.channel_fmt, ch);
    }

    // -- bagli cihazlar --

    private String displayName(String mac, String host) {
        String nick = Store.get(this).nickname(mac);
        if (nick != null) return nick;
        if (!TextUtils.isEmpty(host) && !"null".equals(host)) {
            Store.get(this).setHost(mac, host);
            return host;
        }
        String saved = Store.get(this).host(mac);
        return saved != null ? saved : getString(R.string.unknown_device);
    }

    private void renderClientsCard(JSONArray clients) {
        root.addView(Ui.sectionLabel(this, getString(R.string.section_clients)));
        if (clients == null || clients.length() == 0) {
            root.addView(Ui.group(this, Ui.item(this, getString(R.string.no_clients), null, null)));
        } else {
            JSONArray blocked = state.optJSONArray("blocked");
            List<View> rows = new ArrayList<>();
            for (int i = 0; i < clients.length(); i++) {
                JSONObject c = clients.optJSONObject(i);
                if (c == null) continue;
                String mac = c.optString("mac");
                rows.add(clientRow(c.optString("host", null), c.optString("ip", null), mac, containsMac(blocked, mac)));
            }
            root.addView(Ui.group(this, rows.toArray(new View[0])));
        }

        // Guvenilen ve engellenen cihazlar: listeler uzayabilir, bu yuzden satir icinde degil, dokununca acilan pencerede.
        int nTrusted = Store.get(this).trustedMacs().size(), nBlocked = blockedMacs().size();
        List<View> manage = new ArrayList<>();
        if (nTrusted > 0) {
            LinearLayout r = Ui.item(this, getString(R.string.section_trusted), devicesText(nTrusted), null);
            r.setOnClickListener(v -> showDeviceList(true));
            manage.add(r);
        }
        if (nBlocked > 0) {
            LinearLayout r = Ui.item(this, getString(R.string.section_blocked), devicesText(nBlocked), null);
            r.setOnClickListener(v -> showDeviceList(false));
            manage.add(r);
        }
        if (!manage.isEmpty()) {
            root.addView(Ui.sectionLabel(this, getString(R.string.section_manage)));
            root.addView(Ui.group(this, manage.toArray(new View[0])));
        }
    }

    private List<String> blockedMacs() {
        List<String> l = new ArrayList<>();
        JSONArray blocked = state != null ? state.optJSONArray("blocked") : null;
        if (blocked != null) for (int i = 0; i < blocked.length(); i++) l.add(blocked.optString(i));
        return l;
    }

    private String sinceText(long t, int fmtRes) {
        if (t <= 0) return "";
        CharSequence rel = DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        return getString(fmtRes, rel);
    }

    /** Guvenilen / engellenen cihazlarin listesi: ad (varsa), MAC, ne zamandir, ve kaldirma eylemi. */
    private void showDeviceList(boolean trusted) {
        Store st = Store.get(this);
        List<String> macs = trusted ? st.trustedMacs() : blockedMacs();
        LinearLayout col = Ui.column(this);
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        AlertDialog[] dlg = new AlertDialog[1];
        for (String mac : macs) {
            String nick = st.nickname(mac), host = st.host(mac);
            boolean unnamed = nick == null && host == null;
            String name = nick != null ? nick : host != null ? host : getString(R.string.unknown_device);
            String since = trusted ? sinceText(st.trustedSince(mac), R.string.since_trusted)
                    : sinceText(st.blockedSince(mac), R.string.since_blocked);
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            LinearLayout info = Ui.column(this);
            info.addView(Ui.text(this, name, 16, "on_surface", true));
            String detail = mac + (since.isEmpty() ? "" : " · " + since);
            if (!detail.isEmpty()) {
                TextView d = Ui.text(this, detail, 13, "on_surface_variant", false);
                d.setPadding(0, Ui.dp(this, 2), 0, 0);
                info.addView(d);
            }
            row.addView(info, Ui.weighted(1));
            // Dokununca ad ver (ozellikle rastgele MAC kullanan, adi bilinmeyen cihazlar icin)
            row.setClickable(true);
            row.setOnClickListener(v -> {
                dlg[0].dismiss();
                renameDialog(mac, host, () -> showDeviceList(trusted));
            });
            TextView action = Ui.tonalButton(this, getString(trusted ? R.string.untrust : R.string.unblock));
            action.setOnClickListener(v -> {
                dlg[0].dismiss();
                if (trusted) { st.setTrusted(mac, false); render(); } else act("unblock", mac);
            });
            row.addView(action);
            col.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle(trusted ? R.string.section_trusted : R.string.section_blocked)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private List<String> offlineBlockedMacs(JSONArray clients) {
        JSONArray blocked = state.optJSONArray("blocked");
        List<String> offline = new ArrayList<>();
        if (blocked != null) {
            for (int i = 0; i < blocked.length(); i++) {
                String mac = blocked.optString(i);
                if (!containsMac(clients, mac)) offline.add(mac);
            }
        }
        return offline;
    }

    private boolean containsMac(JSONArray arr, String value) {
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            String mac = c != null ? c.optString("mac") : arr.optString(i);
            if (value.equals(mac)) return true;
        }
        return false;
    }

    private View clientRow(String host, String ip, String mac, boolean blocked) {
        Store st = Store.get(this);
        String name = displayName(mac, host);
        boolean unknown = name.equals(getString(R.string.unknown_device));

        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 10));
        card.setClickable(true);
        card.setOnClickListener(v -> renameDialog(mac, host));

        LinearLayout top = Ui.row(this);
        TextView av = Ui.avatar(this, unknown ? "?" : name.substring(0, 1).toUpperCase(Locale.getDefault()));
        top.addView(av, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout info = Ui.column(this);
        info.addView(Ui.text(this, name, 16, "on_surface", true));
        TextView detail = Ui.text(this, (ip != null ? ip + " · " : "") + mac, 13, "on_surface_variant", false);
        detail.setPadding(0, Ui.dp(this, 2), 0, 0);
        info.addView(detail);

        List<String> extra = new ArrayList<>();
        if (st.isTrusted(mac)) extra.add(getString(R.string.trusted_label));
        long since = st.connectedSince(mac);
        if (since > 0) extra.add(getString(R.string.connected_for, fmtMin((System.currentTimeMillis() - since) / 60_000)));
        if (!extra.isEmpty()) {
            TextView e = Ui.text(this, TextUtils.join(" · ", extra), 13, "primary", false);
            e.setPadding(0, Ui.dp(this, 2), 0, 0);
            info.addView(e);
        }
        LinearLayout.LayoutParams ilp = Ui.weighted(1);
        ilp.leftMargin = Ui.dp(this, 16);
        top.addView(info, ilp);
        card.addView(top);

        LinearLayout actions = Ui.row(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView kick = Ui.textButton(this, getString(R.string.kick));
        kick.setOnClickListener(v -> act("kick", mac));
        actions.addView(kick);
        TextView block = Ui.textButton(this, getString(blocked ? R.string.unblock : R.string.block));
        block.setOnClickListener(v -> act(blocked ? "unblock" : "block", mac));
        actions.addView(block);
        card.addView(actions);
        return card;
    }

    /** Cihaza ad ver / guvenilir yap. */
    private void renameDialog(String mac, String host) {
        renameDialog(mac, host, null);
    }

    private void renameDialog(String mac, String host, Runnable after) {
        Store st = Store.get(this);
        String nick = st.nickname(mac);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(displayName(mac, host));
        if (nick != null) {
            input.setText(nick);
            input.setSelection(nick.length());
        }
        FrameLayout box = new FrameLayout(this);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.device_name)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    st.setNickname(mac, input.getText().toString().trim());
                    render();
                    if (after != null) after.run();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> { if (after != null) after.run(); })
                .setNeutralButton(st.isTrusted(mac) ? R.string.untrust : R.string.trust, (d, w) -> {
                    st.setTrusted(mac, !st.isTrusted(mac));
                    render();
                    if (after != null) after.run();
                })
                .show();
    }

    // -- kullanim --

    private void renderUsageCard() {
        long down = state.optLong("usageDown", -1), up = state.optLong("usageUp", -1);
        List<View> rows = new ArrayList<>();
        if (down >= 0) {
            rows.add(Ui.item(this, getString(R.string.usage_data), getString(R.string.usage_data_summary,
                    Formatter.formatFileSize(this, down), Formatter.formatFileSize(this, up)), null));
        }
        long onAt = Store.get(this).apOnAt();
        if (onAt > 0) {
            rows.add(Ui.item(this, getString(R.string.usage_time), fmtMin((System.currentTimeMillis() - onAt) / 60_000), null));
        }
        if (rows.isEmpty()) return;
        root.addView(Ui.sectionLabel(this, getString(R.string.section_usage)));
        root.addView(Ui.group(this, rows.toArray(new View[0])));
    }

    // -- radyo / gizlilik --

    private String macLabel(int v) {
        return getString(v == 0 ? R.string.mac_none : v == 1 ? R.string.mac_persistent : R.string.mac_each);
    }

    private void renderRadioCard() {
        root.addView(Ui.sectionLabel(this, getString(R.string.section_radio)));
        boolean axBase = state.optBoolean("ax"), beBase = state.optBoolean("be");
        boolean axNow = pendingAx != null ? pendingAx : axBase, beNow = pendingBe != null ? pendingBe : beBase;
        LinearLayout ax = switchRow(getString(R.string.wifi6), getString(R.string.wifi6_summary), axNow, checked -> {
            pendingAx = checked == axBase ? null : checked;
            syncApplyBar();
        });
        LinearLayout be = switchRow(getString(R.string.wifi7), getString(R.string.wifi7_summary), beNow, checked -> {
            pendingBe = checked == beBase ? null : checked;
            syncApplyBar();
        });
        int macBase = state.optInt("macRand", 2);
        int macNow = pendingMacRand != null ? pendingMacRand : macBase;
        LinearLayout mac = Ui.item(this, getString(R.string.mac_privacy), macLabel(macNow), null);
        mac.setOnClickListener(v -> {
            int[] vals = {1, 2, 0};
            String[] labels = {macLabel(1), macLabel(2), macLabel(0)};
            int checked = 0;
            for (int i = 0; i < vals.length; i++) if (vals[i] == macNow) checked = i;
            pick(R.string.mac_privacy, labels, checked, which -> {
                pendingMacRand = vals[which] == macBase ? null : vals[which];
                render();
            });
        });
        root.addView(Ui.group(this, ax, be, mac));
    }

    // -- otomasyon --

    private void renderAutomationCard(boolean apOn) {
        Store st = Store.get(this);
        root.addView(Ui.sectionLabel(this, getString(R.string.section_automation)));

        // Sistemin kendi "cihaz bagli degilse hotspot'u kapat" ayari (hotspot acikken anlik uygulanir).
        boolean idleKnown = state != null && state.has("autoShutdown");
        long idleMs = idleKnown ? state.optLong("shutdownMs", -1) : -1;
        int idle = !idleKnown ? 0 : !state.optBoolean("autoShutdown") ? 0 : idleMs <= 0 ? 10 : (int) (idleMs / 60_000);
        LinearLayout idleRow = Ui.item(this, getString(R.string.idle_off),
                idle == 0 ? getString(R.string.off_label) : getString(R.string.idle_off_summary, fmtMin(idle)), null);
        idleRow.setOnClickListener(v -> pickMinutes(R.string.idle_off, IDLE_MIN, idle, m -> act("idle", String.valueOf(m))));

        int timer = st.timerOffMin();
        LinearLayout timerRow = Ui.item(this, getString(R.string.timer_off),
                timer == 0 ? getString(R.string.off_label) : getString(R.string.timer_off_summary, fmtMin(timer)), null);
        timerRow.setOnClickListener(v -> pickMinutes(R.string.timer_off, TIMER_MIN, timer, m -> {
            st.setTimerOffMin(m);
            automationChanged(apOn);
        }));

        int daily = st.dailyOffMinute();
        LinearLayout dailyRow = Ui.item(this, getString(R.string.daily_off),
                daily < 0 ? getString(R.string.off_label) : String.format(Locale.getDefault(), "%02d:%02d", daily / 60, daily % 60), null);
        dailyRow.setOnClickListener(v -> pick(R.string.daily_off,
                new String[]{getString(R.string.off_label), getString(R.string.pick_time)}, daily < 0 ? 0 : 1, which -> {
                    if (which == 0) {
                        st.setDailyOffMinute(-1);
                        automationChanged(apOn);
                    } else {
                        int h = daily < 0 ? 2 : daily / 60, m = daily < 0 ? 0 : daily % 60;
                        new TimePickerDialog(this, (tp, hh, mm) -> {
                            st.setDailyOffMinute(hh * 60 + mm);
                            automationChanged(apOn);
                        }, h, m, DateFormat.is24HourFormat(this)).show();
                    }
                }));

        LinearLayout notifyRow = switchRow(getString(R.string.notify_new), getString(R.string.notify_new_summary),
                st.notifyNew(), checked -> {
                    st.setNotifyNew(checked);
                    automationChanged(apOn);
                });

        LinearLayout trustedRow = switchRow(getString(R.string.trusted_only), getString(R.string.trusted_only_summary),
                st.trustedOnly(), checked -> {
                    if (checked) trustCurrentClients();  // su an bagli olanlar kendiliginden guvenilir sayilir
                    st.setTrustedOnly(checked);
                    automationChanged(apOn);
                });

        root.addView(Ui.group(this, idleRow, timerRow, dailyRow, notifyRow, trustedRow));
    }

    private void trustCurrentClients() {
        JSONArray clients = state != null ? state.optJSONArray("clients") : null;
        if (clients == null) return;
        for (int i = 0; i < clients.length(); i++) {
            JSONObject c = clients.optJSONObject(i);
            if (c != null) Store.get(this).setTrusted(c.optString("mac"), true);
        }
    }

    /** Otomasyon ayari degisti: alarmlari yenile, gerekiyorsa izleme servisini baslat. */
    private void automationChanged(boolean apOn) {
        Store st = Store.get(this);
        ActionReceiver.scheduleDaily(this);
        if (apOn) {
            int timer = st.timerOffMin();
            if (timer > 0) {
                long onAt = st.apOnAt() > 0 ? st.apOnAt() : System.currentTimeMillis();
                ActionReceiver.setTimerAlarm(this, Math.max(timer * 60_000L - (System.currentTimeMillis() - onAt), 5_000));
            } else {
                ActionReceiver.setTimerAlarm(this, 0);
            }
        }
        if (st.needsDaemon()) io.execute(() -> {
            try {
                Bridge.startDaemon(this);
            } catch (Exception ignored) { }
        });
        render();
    }

    // -- gelismis --

    private void renderAdvancedCard() {
        Store st = Store.get(this);
        root.addView(Ui.sectionLabel(this, getString(R.string.section_advanced)));
        LinearLayout autoAdb = switchRow(getString(R.string.auto_adb), getString(R.string.auto_adb_summary),
                st.autoAdb(), checked -> {
                    st.setAutoAdb(checked);
                    if (checked) NetReceiver.register(this); else NetReceiver.unregister(this);
                });
        root.addView(Ui.group(this, autoAdb));
    }

    // ---- diyalog yardimcilari --------------------------------------------------------------------

    private void pickMinutes(int titleRes, int[] values, int current, Consumer<Integer> onPick) {
        String[] labels = new String[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = minLabel(values[i]);
            if (values[i] == current) checked = i;
        }
        pick(titleRes, labels, checked, which -> onPick.accept(values[which]));
    }

    private void pick(int titleRes, String[] labels, int checked, Consumer<Integer> onPick) {
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    onPick.accept(which);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Dogrulayici: hata metni dondurur, gecerliyse null. */
    private interface Validator {
        String check(String value);
    }

    /** neutralLabel != null ise ek bir "Kopyala" dugmesi gosterir (metni panoya kopyalar). */
    private void editText(int titleRes, String initial, boolean password, String neutralLabel,
                          Validator validator, Consumer<String> onOk) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initial);
        input.setSelection(initial.length());
        input.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        FrameLayout box = new FrameLayout(this);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        box.addView(input);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null);
        if (neutralLabel != null) b.setNeutralButton(neutralLabel, null);
        AlertDialog dlg = b.create();
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
        // Dogrulama basarisizsa / kopyalarken diyalog kapanmasin: varsayilan kapatma davranisini gecersiz kil.
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString();
            String err = validator.check(value);
            if (err != null) {
                input.setError(err);
                return;
            }
            dlg.dismiss();
            onOk.accept(value);
        });
        if (neutralLabel != null) {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.password), input.getText().toString()));
                Toast.makeText(this, R.string.password_copied, Toast.LENGTH_SHORT).show();
            });
        }
        input.requestFocus();
    }
}
