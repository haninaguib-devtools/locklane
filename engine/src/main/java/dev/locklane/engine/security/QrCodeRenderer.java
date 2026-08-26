package dev.locklane.engine.security;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * Turns a string into a QR code the browser can display directly (#88) — a
 * {@code data:image/png;base64,...} URI, which drops straight into an {@code <img src>} with
 * no second request and no image file to serve, cache, or clean up.
 *
 * <p>Only zxing's {@code core} artifact is involved: it produces the black-and-white module
 * grid, and the PNG is written with the JDK's own {@code ImageIO}, which is why the
 * {@code zxing:javase} companion artifact is not a dependency here.
 */
@Component
public class QrCodeRenderer {

    /**
     * Pixels per side. Large enough that a phone camera reads it off a laptop screen without
     * the browser having to scale it up and blur the module edges.
     */
    private static final int SIZE_PIXELS = 320;

    private static final int WHITE = 0xffffffff;
    private static final int BLACK = 0xff000000;

    public String toPngDataUri(String content) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, SIZE_PIXELS, SIZE_PIXELS,
                    Map.of(
                            // M recovers from ~15% damage — the usual choice for a code read off a
                            // screen, where there is no print smudging to survive.
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            // The quiet zone the spec requires; without it a scanner can fail to
                            // find the code against a busy page background.
                            EncodeHintType.MARGIN, 2));
        } catch (WriterException e) {
            throw new IllegalStateException("Could not encode the QR code", e);
        }

        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                image.setRGB(x, y, matrix.get(x, y) ? BLACK : WHITE);
            }
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "PNG", png);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the QR code as PNG", e);
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
    }
}
