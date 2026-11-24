import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * 암호화된 violations.log 해독 도구.
 *
 * 사용법: java DecryptLog.java <violations.log> <비밀 키 파일> [출력 파일]
 * (출력 파일을 주지 않으면 화면에 출력)
 */
public class DecryptLog {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("사용법: java DecryptLog.java <violations.log> <비밀 키 파일> [출력 파일]");
            System.exit(1);
        }

        String keyText = Files.readString(Path.of(args[1])).replaceAll("\\s", "");
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyText)));

        PrintStream out = args.length >= 3
                ? new PrintStream(Files.newOutputStream(Path.of(args[2])), true, StandardCharsets.UTF_8)
                : new PrintStream(System.out, true, StandardCharsets.UTF_8);

        SecretKey session = null;
        int lineNumber = 0, failed = 0;
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            lineNumber++;
            try {
                if (line.startsWith("KEY ")) {
                    Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
                    rsa.init(Cipher.DECRYPT_MODE, privateKey);
                    session = new SecretKeySpec(rsa.doFinal(Base64.getDecoder().decode(line.substring(4))), "AES");
                } else if (line.startsWith("LOG ")) {
                    if (session == null) {
                        throw new IllegalStateException("KEY 줄이 없음");
                    }
                    byte[] data = Base64.getDecoder().decode(line.substring(4));
                    Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
                    aes.init(Cipher.DECRYPT_MODE, session, new GCMParameterSpec(128, Arrays.copyOf(data, 12)));
                    out.println(new String(aes.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8));
                } else if (!line.isBlank()) {
                    out.println(line); // 암호화 전 버전의 평문 기록
                }
            } catch (Exception e) {
                failed++;
                out.println("[" + lineNumber + "번째 줄 해독 실패: " + e.getMessage() + "]");
            }
        }

        if (failed > 0) {
            System.err.println(failed + "줄을 해독하지 못했습니다 (다른 키로 암호화되었거나 파일이 손상됨).");
        }
    }
}
