package com.mbaha.hotspottile;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * Kucuk View yardimcilari. XML layout kullanilmiyor: tek ekranlik bir panel icin inflate maliyeti ve
 * ek kaynak dosyalarindan kacinmak daha az ek yuk getirir.
 *
 * Gorsel dil Android 16/17'nin Ayarlar uygulamasindaki Material 3 Expressive duzenidir: her satir
 * AYRI bir kart, kartlar arasi 2dp bosluk, grubun dis kosesi buyuk (24dp) / ic kosesi kucuk (4dp),
 * cizgi ayirici yok, ana renkler sistemin dinamik (Material You) paletinden (system_*_light/dark)
 * okunur, anahtar M3 anahtaridir (ikonlu basparmak).
 */
final class Ui {
    private static final int OUTER = 24, INNER = 4, GAP = 2;

    private Ui() { }

    // ---- olcu / renk -----------------------------------------------------------------------------

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Material 3 rolunu (orn. "surface_container", "on_surface_variant") sistemin dinamik rengine cevirir.
     * Cihazda kaynak yoksa (cok eski/ozel ROM) notr bir griye duser.
     */
    static int role(Context c, String role) {
        String name = "system_" + role + (night(c) ? "_dark" : "_light");
        int id = c.getResources().getIdentifier(name, "color", "android");
        if (id != 0) {
            try {
                return c.getColor(id);
            } catch (Exception ignored) { }
        }
        return night(c) ? 0xFFE6E1E9 : 0xFF1C1B1F;
    }

    /** Temadan bagimsiz ACIK palet rengi (QR gibi, koyu temada bile acik zemin isteyen ogeler icin). */
    static int roleLight(Context c, String role) {
        int id = c.getResources().getIdentifier("system_" + role + "_light", "color", "android");
        try {
            if (id != 0) return c.getColor(id);
        } catch (Exception ignored) { }
        return 0xFF1C1B1F;
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    static int error(Context c) {
        return night(c) ? 0xFFF2B8B5 : 0xFFB3261E;
    }

    private static Typeface medium() {
        return Typeface.create(Typeface.DEFAULT, 500, false);
    }

    // ---- sekiller --------------------------------------------------------------------------------

    private static GradientDrawable shape(int color, float tl, float tr, float br, float bl) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        return g;
    }

    private static RippleDrawable ripple(Context c, GradientDrawable content, int rippleColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadii(content.getCornerRadii());
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    private static GradientDrawable pill(Context c, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, 100));
        return g;
    }

    // ---- yerlesim --------------------------------------------------------------------------------

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams fillWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** Yalnizca ICERIKLI (TextView vb.) esnek cocuklar icin: duz View icin kullanma (bkz. PanelActivity). */
    static LinearLayout.LayoutParams weighted(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.weight = w;
        return lp;
    }

    // ---- metin -----------------------------------------------------------------------------------

    static TextView text(Context c, CharSequence s, float sp, String colorRole, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(role(c, colorRole));
        if (medium) t.setTypeface(medium());
        return t;
    }

    /** Ust cubuk: yuvarlak geri dugmesi + (kalin olmayan) buyuk baslik, Ayarlar alt sayfalari gibi. */
    static LinearLayout topBar(Context c, String title, View.OnClickListener onBack) {
        LinearLayout bar = row(c);
        bar.setPadding(dp(c, 8), dp(c, 12), dp(c, 8), dp(c, 12));

        android.widget.ImageView back = new android.widget.ImageView(c);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setColorFilter(role(c, "on_surface"));
        back.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        back.setContentDescription(c.getString(R.string.back));
        back.setBackground(ripple(c, shape(role(c, "surface_container_highest"), dp(c, 100), dp(c, 100), dp(c, 100), dp(c, 100)),
                withAlpha(role(c, "on_surface"), 31)));
        back.setClickable(true);
        back.setOnClickListener(onBack);
        bar.addView(back, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));

        TextView t = text(c, title, 22, "on_surface", false);
        LinearLayout.LayoutParams lp = weighted(1);
        lp.leftMargin = dp(c, 20);
        bar.addView(t, lp);
        return bar;
    }

    static TextView sectionLabel(Context c, String text) {
        TextView t = text(c, text, 14, "primary", true);
        t.setPadding(dp(c, 16), dp(c, 24), dp(c, 16), dp(c, 8));
        return t;
    }

    // ---- kart grubu ------------------------------------------------------------------------------

    /**
     * Satirlari tek bir "grup" olarak dizer: her satir ayri kart, ilkinin ust / sonuncunun alt kosesi
     * buyuk, aradakiler kucuk yuvarlak. Satirda tiklama dinleyicisi varsa dokunma dalgasi eklenir.
     */
    static LinearLayout group(Context c, View... items) {
        LinearLayout g = column(c);
        int n = items.length;
        float out = dp(c, OUTER), in = dp(c, INNER);
        int fill = role(c, "surface_bright");
        int rip = withAlpha(role(c, "on_surface"), 26);
        for (int i = 0; i < n; i++) {
            float top = i == 0 ? out : in;
            float bottom = i == n - 1 ? out : in;
            GradientDrawable bg = shape(fill, top, top, bottom, bottom);
            items[i].setBackground(items[i].isClickable() ? ripple(c, bg, rip) : bg);
            LinearLayout.LayoutParams lp = fillWidth();
            if (i > 0) lp.topMargin = dp(c, GAP);
            g.addView(items[i], lp);
        }
        return g;
    }

    /** Baslik + (varsa) alt baslik + (varsa) sondaki gorunum olan tek satir. */
    static LinearLayout item(Context c, CharSequence title, CharSequence subtitle, View trailing) {
        LinearLayout row = row(c);
        row.setMinimumHeight(dp(c, 64));
        row.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));

        LinearLayout info = column(c);
        info.addView(text(c, title, 16, "on_surface", true));
        if (subtitle != null) {
            TextView s = text(c, subtitle, 14, "on_surface_variant", false);
            s.setPadding(0, dp(c, 2), 0, 0);
            info.addView(s);
        }
        row.addView(info, weighted(1));

        if (trailing != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(c, 16);
            row.addView(trailing, lp);
        }
        return row;
    }

    // ---- dugmeler --------------------------------------------------------------------------------

    private static TextView button(Context c, String label, int bg, int fg, int rippleColor, int padH) {
        TextView t = text(c, label, 14, "on_surface", true);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 40));
        t.setPadding(dp(c, padH), dp(c, 8), dp(c, padH), dp(c, 8));
        GradientDrawable shape = pill(c, bg);
        t.setBackground(ripple(c, shape, rippleColor));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** Dolu (birincil) hap dugme. */
    static TextView filledButton(Context c, String label) {
        return button(c, label, role(c, "primary"), role(c, "on_primary"),
                withAlpha(role(c, "on_primary"), 40), 24);
    }

    /** Sistemdeki Wi-Fi hotspot ekranindaki gibi: dikey ayirici + QR kutucugu (ag adi satirinin sonunda). */
    static LinearLayout qrButton(Context c, View.OnClickListener onClick) {
        LinearLayout box = row(c);
        View div = new View(c);
        div.setBackgroundColor(withAlpha(role(c, "outline"), 110));
        box.addView(div, new LinearLayout.LayoutParams(dp(c, 1), dp(c, 32)));
        android.widget.ImageView iv = new android.widget.ImageView(c);
        iv.setImageResource(R.drawable.ic_qr);
        iv.setColorFilter(role(c, "primary"));
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        iv.setBackground(ripple(c, shape(Color.TRANSPARENT, dp(c, 100), dp(c, 100), dp(c, 100), dp(c, 100)),
                withAlpha(role(c, "primary"), 40)));
        iv.setClickable(true);
        iv.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48));
        lp.leftMargin = dp(c, 8);
        box.addView(iv, lp);
        return box;
    }

    /** Cercheveli (outlined) hap dugme: sistemdeki "Quick Share" gibi ikincil eylem. */
    static TextView outlinedButton(Context c, String label) {
        TextView t = text(c, label, 14, "primary", true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 40));
        t.setPadding(dp(c, 24), dp(c, 8), dp(c, 24), dp(c, 8));
        GradientDrawable g = pill(c, Color.TRANSPARENT);
        g.setStroke(dp(c, 1), role(c, "outline"));
        t.setBackground(ripple(c, g, withAlpha(role(c, "primary"), 40)));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** Duz metin dugme (kutusuz). */
    static TextView textButton(Context c, String label) {
        return button(c, label, Color.TRANSPARENT, role(c, "primary"),
                withAlpha(role(c, "primary"), 40), 12);
    }

    /** Sessiz tonlu hap dugme (satir ici aksiyonlar icin). */
    static TextView tonalButton(Context c, String label) {
        return button(c, label, role(c, "secondary_container"), role(c, "on_secondary_container"),
                withAlpha(role(c, "on_secondary_container"), 40), 16);
    }

    /** Bant secimi gibi bir "birbirine bagli dugme grubu": secili olan dolu hap, digerleri tonlu. */
    static LinearLayout segmented(Context c, String[] labels, String[] values, String selected, Consumer<String> onPick) {
        LinearLayout row = row(c);
        TextView[] views = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView t = text(c, labels[i], 14, "on_surface", true);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(c, 48));
            t.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
            t.setClickable(true);
            t.setFocusable(true);
            LinearLayout.LayoutParams lp = weighted(1);
            if (i > 0) lp.leftMargin = dp(c, 4);
            row.addView(t, lp);
            views[i] = t;
        }
        Runnable[] paint = new Runnable[1];
        String[] current = {selected};
        paint[0] = () -> {
            for (int i = 0; i < views.length; i++) {
                boolean sel = values[i].equals(current[0]);
                views[i].setTextColor(role(c, sel ? "on_primary" : "on_surface"));
                float r = sel ? dp(c, 100) : dp(c, 12);
                GradientDrawable bg = shape(sel ? role(c, "primary") : role(c, "surface_container_highest"), r, r, r, r);
                views[i].setBackground(ripple(c, bg, withAlpha(role(c, "on_surface"), 26)));
            }
        };
        for (int i = 0; i < views.length; i++) {
            String v = values[i];
            views[i].setOnClickListener(x -> {
                current[0] = v;
                paint[0].run();
                onPick.accept(v);
            });
        }
        paint[0].run();
        return row;
    }

    /** Kucuk daire avatar (cihaz adinin bas harfi). */
    static TextView avatar(Context c, String letter) {
        TextView t = text(c, letter, 18, "on_primary_container", true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(pill(c, role(c, "primary_container")));
        return t;
    }

    // ---- M3 anahtar ------------------------------------------------------------------------------

    /**
     * Sistemin Ayarlar'indaki gibi Material 3 anahtari: 52x32dp hap iz, 24dp basparmak; acikken iz
     * vurgu rengiyle dolar ve basparmakta bir onay isareti, kapaliyken cerceveli iz ve capraz (x) gorunur.
     */
    static final class M3Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArgbEvaluator eval = new ArgbEvaluator();
        private final int trackOff, trackOn, thumbOff, thumbOn, outline, iconOff, iconOn;
        private final float w, h, thumbR, stroke;
        private boolean checked;
        private float progress; // 0 = kapali, 1 = acik
        private Consumer<Boolean> listener;

        M3Switch(Context c) {
            super(c);
            trackOff = role(c, "surface_container_highest");
            trackOn = role(c, "primary");
            thumbOff = role(c, "outline");
            thumbOn = role(c, "on_primary");
            outline = role(c, "outline");
            iconOff = role(c, "surface_container_highest");
            iconOn = role(c, "primary");
            w = dp(c, 52);
            h = dp(c, 32);
            thumbR = dp(c, 12);
            stroke = dp(c, 2);
            paint.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true);
            setFocusable(true);
            setOnClickListener(v -> toggle());
        }

        /** Kullanici dokunusunda cagrilir; programatik setChecked() bunu tetiklemez. */
        void setOnToggle(Consumer<Boolean> l) {
            listener = l;
        }

        void setChecked(boolean on) {
            checked = on;
            progress = on ? 1f : 0f;
            invalidate();
        }

        boolean isChecked() {
            return checked;
        }

        void toggle() {
            checked = !checked;
            ValueAnimator a = ValueAnimator.ofFloat(progress, checked ? 1f : 0f);
            a.setDuration(200);
            a.addUpdateListener(an -> {
                progress = (float) an.getAnimatedValue();
                invalidate();
            });
            a.start();
            if (listener != null) listener.accept(checked);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension((int) w, (int) h);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float p = progress;
            // iz
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((int) eval.evaluate(p, trackOff, trackOn));
            cv.drawRoundRect(0, 0, w, h, h / 2, h / 2, paint);
            if (p < 1f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setColor(withAlpha(outline, (int) (255 * (1f - p))));
                cv.drawRoundRect(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2,
                        h / 2 - stroke / 2, h / 2 - stroke / 2, paint);
            }
            // basparmak
            float inset = h / 2;
            float cx = inset + (w - 2 * inset) * p;
            float cy = h / 2;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((int) eval.evaluate(p, thumbOff, thumbOn));
            cv.drawCircle(cx, cy, thumbR, paint);
            // ikon
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            float s = dp(getContext(), 4.5f);
            if (p < 0.5f) {
                paint.setColor(withAlpha(iconOff, (int) (255 * (1f - 2 * p))));
                cv.drawLine(cx - s, cy - s, cx + s, cy + s, paint);
                cv.drawLine(cx - s, cy + s, cx + s, cy - s, paint);
            } else {
                paint.setColor(withAlpha(iconOn, (int) (255 * (2 * p - 1f))));
                cv.drawLine(cx - s, cy + 0.2f * s, cx - s * 0.3f, cy + s * 0.8f, paint);
                cv.drawLine(cx - s * 0.3f, cy + s * 0.8f, cx + s, cy - s * 0.7f, paint);
            }
        }
    }
}
