package com.mbaha.hotspottile;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Kucuk, bagimliliksiz QR kod uretici (bayt modu, hata duzeltme seviyesi M, surum 1-10; Wi-Fi
 * baglantisi icin yeterli: ~213 bayta kadar). Algoritma ISO/IEC 18004'e gore: Reed-Solomon (GF(256),
 * 0x11D), sablonlar, 8 maske ve ceza puani ile en iyi maskenin secimi.
 */
final class Qr {
    private Qr() { }

    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int[][] ALIGN = {null, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}};

    /** Wi-Fi QR icerigi: WIFI:T:WPA;S:ad;P:sifre;H:true;; */
    static String wifiPayload(String ssid, String pass, boolean open, boolean sae, boolean hidden) {
        StringBuilder sb = new StringBuilder("WIFI:T:").append(open ? "nopass" : sae ? "SAE" : "WPA")
                .append(";S:").append(esc(ssid)).append(';');
        if (!open) sb.append("P:").append(esc(pass)).append(';');
        if (hidden) sb.append("H:true;");
        return sb.append(';').toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '\\' || ch == ';' || ch == ',' || ch == ':' || ch == '"') sb.append('\\');
            sb.append(ch);
        }
        return sb.toString();
    }

    static Bitmap bitmap(String text, int px, int dark, int light) {
        boolean[][] m = encode(text.getBytes(StandardCharsets.UTF_8));
        int n = m.length, quiet = 2;
        int scale = Math.max(1, px / (n + 2 * quiet));
        int size = (n + 2 * quiet) * scale;
        int[] pix = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int mx = x / scale - quiet, my = y / scale - quiet;
                boolean on = mx >= 0 && my >= 0 && mx < n && my < n && m[my][mx];
                pix[y * size + x] = on ? dark : light;
            }
        }
        return Bitmap.createBitmap(pix, size, size, Bitmap.Config.ARGB_8888);
    }

    // ---- kodlama ---------------------------------------------------------------------------------

    private static int rawCodewords(int v) {
        int modules = (16 * v + 128) * v + 64;
        if (v >= 2) {
            int na = v / 7 + 2;
            modules -= (25 * na - 10) * na - 55;
            if (v >= 7) modules -= 36;
        }
        return modules / 8;
    }

    private static int dataCodewords(int v) {
        return rawCodewords(v) - ECC_PER_BLOCK[v] * BLOCKS[v];
    }

    static boolean[][] encode(byte[] data) {
        int ver = 1;
        while (true) {
            if (ver > 10) throw new IllegalArgumentException("veri cok uzun");
            int ccBits = 8; // surum 1-9 bayt modu; 10+: 16
            if (ver >= 10) ccBits = 16;
            if (4 + ccBits + data.length * 8 <= dataCodewords(ver) * 8) break;
            ver++;
        }
        int ccBits = ver >= 10 ? 16 : 8;
        List<Boolean> bits = new ArrayList<>();
        appendBits(bits, 0x4, 4);
        appendBits(bits, data.length, ccBits);
        for (byte b : data) appendBits(bits, b & 0xff, 8);
        int cap = dataCodewords(ver) * 8;
        appendBits(bits, 0, Math.min(4, cap - bits.size()));
        while (bits.size() % 8 != 0) bits.add(false);
        for (int pad = 0xEC; bits.size() < cap; pad ^= 0xEC ^ 0x11) appendBits(bits, pad, 8);
        byte[] cw = new byte[bits.size() / 8];
        for (int i = 0; i < bits.size(); i++) if (bits.get(i)) cw[i / 8] |= 1 << (7 - i % 8);

        byte[] all = addEccAndInterleave(cw, ver);
        int size = ver * 4 + 17;
        boolean[][] mod = new boolean[size][size];
        boolean[][] fn = new boolean[size][size];
        drawFunctionPatterns(mod, fn, ver);
        drawCodewords(mod, fn, all);

        int best = 0, bestPen = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(mod, fn, mask);
            drawFormat(mod, fn, mask);
            int pen = penalty(mod);
            if (pen < bestPen) {
                bestPen = pen;
                best = mask;
            }
            applyMask(mod, fn, mask); // geri al
        }
        applyMask(mod, fn, best);
        drawFormat(mod, fn, best);
        return mod;
    }

    private static void appendBits(List<Boolean> b, int val, int len) {
        for (int i = len - 1; i >= 0; i--) b.add(((val >>> i) & 1) != 0);
    }

    // ---- Reed-Solomon ----------------------------------------------------------------------------

    private static byte[] addEccAndInterleave(byte[] data, int ver) {
        int blocks = BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver], raw = rawCodewords(ver);
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[][] dataBlocks = new byte[blocks][];
        byte[][] eccBlocks = new byte[blocks][];
        byte[] div = rsDivisor(eccLen);
        int k = 0;
        for (int i = 0; i < blocks; i++) {
            int len = shortLen - eccLen + (i < shortBlocks ? 0 : 1);
            byte[] d = new byte[len];
            System.arraycopy(data, k, d, 0, len);
            k += len;
            dataBlocks[i] = d;
            eccBlocks[i] = rsRemainder(d, div);
        }
        byte[] result = new byte[raw];
        int r = 0;
        for (int i = 0; i < shortLen - eccLen + 1; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i < dataBlocks[j].length) result[r++] = dataBlocks[j][i];
            }
        }
        for (int i = 0; i < eccLen; i++) {
            for (int j = 0; j < blocks; j++) result[r++] = eccBlocks[j][i];
        }
        return result;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) mul(result[j] & 0xff, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = mul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] result = new byte[div.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xff;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= mul(div[i] & 0xff, factor);
        }
        return result;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---- sablonlar -------------------------------------------------------------------------------

    private static void set(boolean[][] mod, boolean[][] fn, int x, int y, boolean dark) {
        mod[y][x] = dark;
        fn[y][x] = true;
    }

    private static void drawFunctionPatterns(boolean[][] mod, boolean[][] fn, int ver) {
        int size = mod.length;
        for (int i = 0; i < size; i++) {
            set(mod, fn, 6, i, i % 2 == 0);
            set(mod, fn, i, 6, i % 2 == 0);
        }
        drawFinder(mod, fn, 3, 3);
        drawFinder(mod, fn, size - 4, 3);
        drawFinder(mod, fn, 3, size - 4);
        int[] pos = ALIGN[ver];
        for (int i = 0; i < pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                boolean corner = (i == 0 && j == 0) || (i == 0 && j == pos.length - 1) || (i == pos.length - 1 && j == 0);
                if (!corner) drawAlign(mod, fn, pos[i], pos[j]);
            }
        }
        drawFormat(mod, fn, 0); // alani ayir
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, c = i / 3;
                set(mod, fn, a, c, b);
                set(mod, fn, c, a, b);
            }
        }
    }

    private static void drawFormat(boolean[][] mod, boolean[][] fn, int mask) {
        int size = mod.length;
        int data = 0 << 3 | mask; // hata duzeltme seviyesi M = 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(mod, fn, 8, i, ((bits >>> i) & 1) != 0);
        set(mod, fn, 8, 7, ((bits >>> 6) & 1) != 0);
        set(mod, fn, 8, 8, ((bits >>> 7) & 1) != 0);
        set(mod, fn, 7, 8, ((bits >>> 8) & 1) != 0);
        for (int i = 9; i < 15; i++) set(mod, fn, 14 - i, 8, ((bits >>> i) & 1) != 0);
        for (int i = 0; i < 8; i++) set(mod, fn, size - 1 - i, 8, ((bits >>> i) & 1) != 0);
        for (int i = 8; i < 15; i++) set(mod, fn, 8, size - 15 + i, ((bits >>> i) & 1) != 0);
        set(mod, fn, 8, size - 8, true);
    }

    private static void drawFinder(boolean[][] mod, boolean[][] fn, int cx, int cy) {
        int size = mod.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) set(mod, fn, x, y, dist != 2 && dist != 4);
            }
        }
    }

    private static void drawAlign(boolean[][] mod, boolean[][] fn, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                set(mod, fn, cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private static void drawCodewords(boolean[][] mod, boolean[][] fn, byte[] data) {
        int size = mod.length, i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < data.length * 8) {
                        mod[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[][] mod, boolean[][] fn, int mask) {
        int size = mod.length;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (mask) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0;
                }
                if (!fn[y][x] && inv) mod[y][x] = !mod[y][x];
            }
        }
    }

    // ---- ceza puani (en iyi maske) ---------------------------------------------------------------

    private static int penalty(boolean[][] m) {
        int n = m.length, result = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < n; a++) {
                int run = 1;
                int[] hist = new int[7];
                int histLen = 0;
                for (int b = 1; b < n; b++) {
                    boolean cur = pass == 0 ? m[a][b] : m[b][a];
                    boolean prev = pass == 0 ? m[a][b - 1] : m[b - 1][a];
                    if (cur == prev) {
                        run++;
                        if (run == 5) result += 3;
                        else if (run > 5) result++;
                    } else {
                        run = 1;
                    }
                }
                // bulucu benzeri desen (1:1:3:1:1 + 4 acik)
                for (int b = 0; b + 10 < n + 0; b++) {
                    boolean[] w = new boolean[11];
                    for (int k = 0; k < 11; k++) w[k] = pass == 0 ? m[a][b + k] : m[b + k][a];
                    if (matches(w, new boolean[]{true, false, true, true, true, false, true, false, false, false, false})
                            || matches(w, new boolean[]{false, false, false, false, true, false, true, true, true, false, true})) {
                        result += 40;
                    }
                }
            }
        }
        for (int y = 0; y < n - 1; y++) {
            for (int x = 0; x < n - 1; x++) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3;
            }
        }
        int dark = 0;
        for (boolean[] row : m) for (boolean b : row) if (b) dark++;
        int total = n * n;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return result + k * 10;
    }

    private static boolean matches(boolean[] a, boolean[] b) {
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }
}
