package com.bbb.exercise.agentdemo.auth.payment;

import com.bbb.exercise.agentdemo.auth.billing.BillingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/** Protocol crypto: RSA SHA-256 signatures and PEM reading. */
final class PaymentCrypto {
    private PaymentCrypto() {}

    static String pem(String path) {
        try {
            return Files.readString(Path.of(path))
                    .replaceAll("-----[^-]+-----", "")
                    .replaceAll("\\s", "");
        } catch (Exception e) {
            throw new BillingException(503, "支付密钥文件无法读取");
        }
    }

    static String sign(String text, String privateKey) {
        try {
            var key =
                    KeyFactory.getInstance("RSA")
                            .generatePrivate(
                                    new PKCS8EncodedKeySpec(
                                            Base64.getDecoder().decode(privateKey)));
            var signature = Signature.getInstance("SHA256withRSA");

            signature.initSign(key);
            signature.update(text.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new BillingException(503, "支付签名失败，请检查私钥");
        }
    }

    static void verify(String text, String signature, String publicKey) {
        try {
            var key =
                    KeyFactory.getInstance("RSA")
                            .generatePublic(
                                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            var verifier = Signature.getInstance("SHA256withRSA");

            verifier.initVerify(key);
            verifier.update(text.getBytes(StandardCharsets.UTF_8));

            if (!verifier.verify(Base64.getDecoder().decode(signature))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new BillingException(401, "支付签名无效");
        }
    }

}
