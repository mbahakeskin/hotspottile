package com.mbaha.hotspottile;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Minimal, bagimliliksiz ADB istemcisi (klasik TCP, TLS yok). Yalnizca "exec:" servisini kullanir.
 * Ilk baglantida telefon "USB hata ayiklamaya izin ver" sorar; "Her zaman izin ver" secilirse anahtar
 * kalici olarak yetkilenir.
 */
final class AdbClient implements Closeable {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_STLS = 0x534c5453;
    private static final int VERSION = 0x01000001;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    // SHA-1 DigestInfo oneki: adb, 20 baytlik token'i sanki bir SHA-1 ozetiymis gibi imzalar.
    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private final KeyPair keys;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int maxData = 4096;

    AdbClient(KeyPair keys) {
        this.keys = keys;
    }

    // ---- anahtar yonetimi ------------------------------------------------------------------------

    static KeyPair loadOrCreateKeys(File dir) throws Exception {
        File priv = new File(dir, "adbkey.pk8");
        File pub = new File(dir, "adbkey.x509");
        if (priv.exists() && pub.exists()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey sk = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv)));
                java.security.PublicKey pk = kf.generatePublic(new X509EncodedKeySpec(readAll(pub)));
                return new KeyPair(pk, sk);
            } catch (Exception ignored) {
                // bozuksa yeniden uret
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        write(priv, kp.getPrivate().getEncoded());
        write(pub, kp.getPublic().getEncoded());
        return kp;
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream fi = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static void write(File f, byte[] b) throws IOException {
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(b);
        }
    }

    /** adb'nin bekledigi (mincrypt) RSA acik anahtar bicimi, base64 + " ad\0". */
    private byte[] publicKeyBlob() {
        RSAPublicKey pk = (RSAPublicKey) keys.getPublic();
        BigInteger n = pk.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        int n0inv = n.modInverse(r32).negate().mod(r32).intValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);

        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(64);
        bb.putInt(n0inv);
        putWords(bb, n);
        putWords(bb, rr);
        bb.putInt(pk.getPublicExponent().intValue());
        String b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP);
        return (b64 + " hotspot@tile\0").getBytes(StandardCharsets.US_ASCII);
    }

    private static void putWords(ByteBuffer bb, BigInteger v) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < 64; i++) {
            bb.putInt(v.shiftRight(32 * i).and(mask).intValue());
        }
    }

    private byte[] sign(byte[] token) throws Exception {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(keys.getPrivate());
        s.update(SHA1_PREFIX);
        s.update(token);
        return s.sign();
    }

    // ---- protokol --------------------------------------------------------------------------------

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(0).putInt(cmd ^ 0xffffffff);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        m.data = new byte[len];
        if (len > 0) in.readFully(m.data);
        return m;
    }

    /** authWaitMs: kullanicinin "izin ver" kutusunu onaylamasi icin beklenecek sure. */
    void connect(String host, int port, int authWaitMs) throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 3000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(authWaitMs);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        send(A_CNXN, VERSION, 256 * 1024, "host::\0".getBytes(StandardCharsets.US_ASCII));
        boolean signed = false;
        while (true) {
            Msg m = read();
            if (m.cmd == A_CNXN) {
                maxData = Math.max(1024, Math.min(m.arg1, 64 * 1024));
                socket.setSoTimeout(15000);
                return;
            }
            if (m.cmd == A_AUTH && m.arg0 == AUTH_TOKEN) {
                if (!signed) {
                    send(A_AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                    signed = true;
                } else {
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob());
                }
                continue;
            }
            throw new IOException("beklenmeyen adb mesaji: 0x" + Integer.toHexString(m.cmd));
        }
    }

    /**
     * Kablosuz hata ayiklama (TLS) portuna baglanir. Eslestirme GEREKMEZ: adbd, TLS istemci sertifikasinin
     * acik anahtarini USB'deki "Her zaman izin ver" listesiyle ayni anahtar listesine karsi dogrular; bu
     * nedenle zaten yetkili olan anahtarimizdan uretilen otomatik imzali sertifika yeterlidir.
     * Akis: host CNXN gonderir -> daemon A_STLS -> host A_STLS ile cevaplar -> TLS el sikisma (host = istemci)
     * -> daemon yeniden CNXN gonderir.
     */
    void connectTls(String host, int port) throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 3000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(8000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        send(A_CNXN, VERSION, 256 * 1024, "host::\0".getBytes(StandardCharsets.US_ASCII));
        Msg m = read();
        if (m.cmd != A_STLS) throw new IOException("TLS beklenirken adb mesaji: 0x" + Integer.toHexString(m.cmd));
        send(A_STLS, m.arg0, 0, null);

        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.3");
        ctx.init(new javax.net.ssl.KeyManager[]{new AdbCert.Km(keys)},
                new javax.net.ssl.TrustManager[]{new AdbCert.TrustAll()}, null);
        javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) ctx.getSocketFactory()
                .createSocket(socket, host, port, true);
        ssl.setUseClientMode(true);
        ssl.setEnabledProtocols(new String[]{"TLSv1.3"});
        ssl.startHandshake();
        socket = ssl;
        in = new DataInputStream(ssl.getInputStream());
        out = ssl.getOutputStream();

        // El sikismadan sonra daemon kendi CNXN'ini gonderir; gelmezse (eski surum) biz gonderip bekleriz.
        try {
            m = read();
        } catch (java.net.SocketTimeoutException e) {
            send(A_CNXN, VERSION, 256 * 1024, "host::\0".getBytes(StandardCharsets.US_ASCII));
            m = read();
        }
        if (m.cmd != A_CNXN) throw new IOException("TLS sonrasi beklenmeyen adb mesaji: 0x" + Integer.toHexString(m.cmd));
        maxData = Math.max(1024, Math.min(m.arg1, 64 * 1024));
        socket.setSoTimeout(15000);
    }

    /** "exec:" servisiyle komutu calistirir, ciktisini dondurur. */
    String exec(String command) throws IOException {
        return service("exec:" + command);
    }

    /** Herhangi bir adb servisini (orn. "tcpip:5555") acar ve kapanana kadar ciktisini toplar. */
    String service(String name) throws IOException {
        send(A_OPEN, 1, 0, (name + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = 0;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        while (true) {
            Msg m = read();
            if (m.cmd == A_OKAY) {
                remote = m.arg0;
            } else if (m.cmd == A_WRTE) {
                bo.write(m.data);
                send(A_OKAY, 1, remote, null);
            } else if (m.cmd == A_CLSE) {
                send(A_CLSE, 1, remote, null);
                break;
            }
        }
        return bo.toString("UTF-8");
    }

    @Override
    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }
}
