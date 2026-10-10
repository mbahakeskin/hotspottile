package com.mbaha.hotspottile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.animation.ValueAnimator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Sistemin "Hotspot'u paylas" sayfasinin karsiligi: tam sayfa, yuvarlak geri dugmesi, kucuk QR simgesi, buyuk
 * baslik, yonca sekilli zemin uzerinde yuvarlak modullu QR (koseler halka + nokta), sifre ve paylas dugmesi.
 * Sistemdeki gibi once parmak izi/ekran kilidiyle dogrulanir ve ekran goruntusune kapatilir. QR ortadan disa
 * dogru beliren bir animasyonla acilir.
 */
public class ShareActivity extends Activity {
    /** Sifreyi gosteren ekran goruntusune/kayda kapali olsun (sistemdeki gibi). Gelistirme onizlemesinde false yapilirsa parmak izi de istenmez. */
    static final boolean SECURE = true;

    private String ssid, pass;
    private int sec;
    private boolean hidden;
    private FrameLayout frame;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        ssid = i.getStringExtra("ssid");
        pass = i.getStringExtra("pass");
        sec = i.getIntExtra("sec", 1);
        hidden = i.getBooleanExtra("hidden", false);
        if (SECURE) getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.role(this, "surface_container"));
        setContentView(frame);
        int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(Ui.night(this) ? 0 : lightBars, lightBars);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            frame.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        authenticateThenShow();
    }

    private void authenticateThenShow() {
        if (!SECURE) {                                   // gelistirme onizlemesi: dogrulama yok
            show();
            return;
        }
        int auth = BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        try {
            if (getSystemService(BiometricManager.class).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
                show();                                  // kilit/parmak izi tanimli degil: dogrulama yok
                return;
            }
            new BiometricPrompt.Builder(this)
                    .setTitle(getString(R.string.share_page_title))
                    .setAllowedAuthenticators(auth)
                    .build()
                    .authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                            show();
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence msg) {
                            finish();
                        }
                    });
        } catch (Exception e) {
            show();
        }
    }

    private void show() {
        frame.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = Ui.column(this);
        col.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), Ui.dp(this, 32));
        scroll.addView(col);
        frame.addView(scroll);

        // yuvarlak geri dugmesi (panel ile ayni)
        LinearLayout bar = Ui.topBar(this, "", v -> finish());
        bar.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
        col.addView(bar);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_qr);
        icon.setColorFilter(Ui.role(this, "primary"));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        ilp.topMargin = Ui.dp(this, 8);
        col.addView(icon, ilp);

        TextView title = Ui.text(this, getString(R.string.share_page_title), 32, "on_surface", false);
        title.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        col.addView(title);
        col.addView(Ui.text(this, getString(R.string.share_page_summary, ssid), 14, "on_surface_variant", false));

        String payload = Qr.wifiPayload(ssid, pass, sec == 0, sec == 3, hidden);
        QrView qr = new QrView(this, Qr.encode(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(Ui.dp(this, 300), Ui.dp(this, 300));
        qlp.gravity = Gravity.CENTER_HORIZONTAL;
        qlp.topMargin = Ui.dp(this, 36);
        col.addView(qr, qlp);

        TextView pw = Ui.text(this, getString(R.string.share_password, pass), 14, "on_surface", false);
        pw.setTypeface(Typeface.MONOSPACE);
        pw.setGravity(Gravity.CENTER);
        pw.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 16));
        pw.setOnLongClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(ssid, pass));
            Toast.makeText(this, R.string.password_copied, Toast.LENGTH_SHORT).show();
            return true;
        });
        col.addView(pw);

        TextView share = Ui.outlinedButton(this, getString(R.string.share_button));
        share.setOnClickListener(v -> startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, ssid, pass)), null)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(share, slp);

        qr.reveal();
    }

    /**
     * Yonca (4 yapraklı) sekilli zemin uzerine cizilen QR. Moduller yuvarlak; uc kose deseni "halka + nokta"
     * (1:1:3:1:1 orani korunur, tarayicilar okur). Acilista modullar ortadan disa dogru buyuyerek belirir.
     */
    static final class QrView extends View {
        private final boolean[][] m;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path blob = new Path();
        private final int bg, fg;
        private float progress;

        QrView(Context c, boolean[][] modules) {
            super(c);
            m = modules;
            bg = Ui.roleLight(c, "primary_container");
            fg = Ui.roleLight(c, "primary");
        }

        void reveal() {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(1100);
            a.setInterpolator(new DecelerateInterpolator(1.4f));
            a.addUpdateListener(an -> {
                progress = (float) an.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            float p = progress;
            // --- zemin: daireden yonca sekline gecis + hafif buyume
            float bgP = Math.min(1f, p * 1.8f);
            float R = Math.min(w, h) / 2f * (0.78f + 0.22f * bgP);
            float k = 0.085f * bgP;                         // yaprak belirginligi
            blob.rewind();
            int steps = 180;
            for (int i = 0; i <= steps; i++) {
                double t = Math.PI * 2 * i / steps;
                // koseler (45 derece) disa, kenar ortalari hafif iceri: dort yaprak
                double r = R * (1 - k * Math.cos(4 * t)) / (1 + k);
                float x = (float) (cx + r * Math.cos(t)), y = (float) (cy + r * Math.sin(t));
                if (i == 0) blob.moveTo(x, y); else blob.lineTo(x, y);
            }
            blob.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.withAlpha(bg, (int) (255 * Math.min(1f, p * 3f))));
            cv.drawPath(blob, paint);

            // --- moduller
            int n = m.length;
            float inner = R * 0.74f * 2f;                   // QR'in yerlesecegi kare (yoncanin icinde)
            float cell = inner / n, ox = cx - inner / 2, oy = cy - inner / 2;
            float maxD = (float) Math.hypot(n / 2f, n / 2f);
            paint.setColor(fg);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (!m[y][x] || inFinder(x, y, n)) continue;
                    float d = (float) Math.hypot(x + 0.5f - n / 2f, y + 0.5f - n / 2f) / maxD;
                    float lp = clamp((p * 1.5f - d * 0.9f) / 0.35f);   // her modul kendi gecikmesiyle belirir
                    if (lp <= 0) continue;
                    cv.drawCircle(ox + (x + 0.5f) * cell, oy + (y + 0.5f) * cell, cell * 0.47f * lp, paint);
                }
            }
            // --- kose desenleri: halka + nokta
            float fp = clamp((p - 0.35f) / 0.5f);
            drawFinder(cv, ox, oy, cell, 0, 0, fp);
            drawFinder(cv, ox, oy, cell, n - 7, 0, fp);
            drawFinder(cv, ox, oy, cell, 0, n - 7, fp);
        }

        private void drawFinder(Canvas cv, float ox, float oy, float cell, int gx, int gy, float fp) {
            if (fp <= 0) return;
            float cx = ox + (gx + 3.5f) * cell, cy = oy + (gy + 3.5f) * cell;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(cell * fp);
            paint.setColor(fg);
            cv.drawCircle(cx, cy, cell * 3f, paint);          // halka: dis yaricap 3.5, ic 2.5 hucre
            paint.setStyle(Paint.Style.FILL);
            cv.drawCircle(cx, cy, cell * 1.5f * fp, paint);   // orta nokta: capi 3 hucre
        }

        private static boolean inFinder(int x, int y, int n) {
            return (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7);
        }

        private static float clamp(float v) {
            return v < 0 ? 0 : v > 1 ? 1 : v;
        }
    }
}
