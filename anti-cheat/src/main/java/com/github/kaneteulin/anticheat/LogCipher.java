package com.github.kaneteulin.anticheat;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * violations.log 암호화.
 * <p>
 * 서버가 켜질 때마다 새 AES-256 키를 만들고, 그 키를 플러그인에 들어 있는 RSA 공개 키로 잠가
 * 로그에 "KEY ..." 줄로 남긴다. 이후 각 기록은 AES-GCM 으로 암호화해 "LOG ..." 줄로 남긴다.
 * 공개 키로는 잠글 수만 있고, 해독하려면 저장소 밖에 보관한 비밀 키가 필요하다 (tools/DecryptLog.java).
 */
final class LogCipher {

    private static final String RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES = "AES/GCM/NoPadding";

    private final PublicKey publicKey;
    private final SecureRandom random = new SecureRandom();
    private SecretKey sessionKey;

    LogCipher(InputStream publicKeyFile) throws IOException, GeneralSecurityException {
        String base64 = new String(publicKeyFile.readAllBytes(), StandardCharsets.US_ASCII).replaceAll("\\s", "");
        publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    /** 새 로그 파일로 넘어갈 때: 다음 기록부터 새 키를 만들어 KEY 줄을 다시 쓴다 (파일마다 따로 해독 가능) */
    void startNewFile() {
        sessionKey = null;
    }

    /** 기록 한 줄을 암호화한 로그 줄들 (이번 실행 또는 이 파일의 첫 기록이면 KEY 줄 포함) */
    List<String> encrypt(String line) throws GeneralSecurityException {
        List<String> out = new ArrayList<>(2);

        if (sessionKey == null) {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, random);
            sessionKey = generator.generateKey();

            Cipher rsa = Cipher.getInstance(RSA);
            rsa.init(Cipher.ENCRYPT_MODE, publicKey);
            out.add("KEY " + Base64.getEncoder().encodeToString(rsa.doFinal(sessionKey.getEncoded())));
        }

        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher aes = Cipher.getInstance(AES);
        aes.init(Cipher.ENCRYPT_MODE, sessionKey, new GCMParameterSpec(128, iv));
        byte[] encrypted = aes.doFinal(line.getBytes(StandardCharsets.UTF_8));

        out.add("LOG " + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array()));
        return out;
    }
}
