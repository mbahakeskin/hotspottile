package com.mbaha.hotspottile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.security.KeyPair;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * adb kablosuz hata ayiklamanin TLS'i icin gereken ogeler: mevcut RSA anahtarimizdan uretilen otomatik
 * imzali X.509 sertifika ve bunu sunan KeyManager. adbd yalniz sertifikanin ACIK ANAHTARINI yetkili
 * anahtar listesiyle karsilastirir; imza/yayinci/gecerlilik onemsizdir. Ek kutuphane (BouncyCastle)
 * olmadan DER'i elle kuruyoruz.
 */
final class AdbCert {
    private AdbCert() { }

    static X509Certificate make(KeyPair kp) throws Exception {
        byte[] sigAlg = seq(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{5, 0}); // sha256WithRSA
        byte[] name = seq(set(seq(oid(new int[]{2, 5, 4, 3}), tlv(0x0c, "hotspot-tile".getBytes("UTF-8")))));
        byte[] validity = seq(tlv(0x17, "200101000000Z".getBytes("US-ASCII")),
                tlv(0x17, "491231235959Z".getBytes("US-ASCII")));
        byte[] tbs = seq(
                tlv(0xa0, tlv(0x02, new byte[]{2})),           // version v3
                tlv(0x02, new byte[]{1}),                       // serial
                sigAlg, name, validity, name,
                kp.getPublic().getEncoded());                   // SubjectPublicKeyInfo (zaten DER)
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] cert = seq(tbs, sigAlg, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
    }

    // ---- minimal DER ---------------------------------------------------------------------------

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, cat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, cat(parts));
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (byte[] p : parts) bo.write(p, 0, p.length);
        return bo.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(tag);
        if (v.length < 128) {
            bo.write(v.length);
        } else if (v.length < 256) {
            bo.write(0x81);
            bo.write(v.length);
        } else {
            bo.write(0x82);
            bo.write(v.length >> 8);
            bo.write(v.length & 0xff);
        }
        bo.write(v, 0, v.length);
        return bo.toByteArray();
    }

    private static byte[] oid(int[] c) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(c[0] * 40 + c[1]);
        for (int i = 2; i < c.length; i++) {
            int v = c[i];
            int n = 0;
            byte[] tmp = new byte[5];
            do {
                tmp[n++] = (byte) (v & 0x7f);
                v >>= 7;
            } while (v > 0);
            for (int j = n - 1; j >= 0; j--) bo.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, bo.toByteArray());
    }

    // ---- JSSE yardimcilari ---------------------------------------------------------------------

    /** Hangi yayincilari istedigine bakmadan daima kendi sertifikamizi sunar. */
    static final class Km extends X509ExtendedKeyManager {
        private final PrivateKey key;
        private final X509Certificate[] chain;

        Km(KeyPair kp) throws Exception {
            key = kp.getPrivate();
            chain = new X509Certificate[]{make(kp)};
        }

        @Override public String[] getClientAliases(String t, Principal[] i) { return new String[]{"adb"}; }
        @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return "adb"; }
        @Override public String chooseEngineClientAlias(String[] t, Principal[] i, SSLEngine e) { return "adb"; }
        @Override public String[] getServerAliases(String t, Principal[] i) { return null; }
        @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return null; }
        @Override public X509Certificate[] getCertificateChain(String a) { return chain; }
        @Override public PrivateKey getPrivateKey(String a) { return key; }
    }

    /** adbd'nin sertifikasi otomatik imzali; kimlik dogrulamayi biz degil adbd yapar (yerel/guvenilir kanal). */
    static final class TrustAll implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
