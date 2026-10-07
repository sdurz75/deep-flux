package org.dual.hexa.ai.llm.adapter.out.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.ImageIO;

import org.dual.hexa.ai.llm.domain.ImageScalingException;
import org.dual.hexa.core.storage.domain.SourceImage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwtSourceImageScalerTest {

    private final AwtSourceImageScaler scaler = new AwtSourceImageScaler();

    /** Rumore casuale: il PNG e' incomprimibile e supera la soglia di riduzione. */
    private static byte[] bigNoisePng(int side) throws IOException {
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int x = 0; x < side; x++) {
            for (int y = 0; y < side; y++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void smallImagesPassThroughUntouched() {
        SourceImage small = new SourceImage(new byte[]{1, 2, 3}, "image/png");

        assertThat(scaler.fitForVision(small)).isSameAs(small);
    }

    @Test
    void bigImagesAreShrunkToTheMaximumSideAsJpeg() throws IOException {
        byte[] png = bigNoisePng(1200);
        assertThat(png.length).isGreaterThan(AwtSourceImageScaler.DOWNSCALE_ABOVE_BYTES);

        SourceImage scaled = scaler.fitForVision(new SourceImage(png, "image/png"));

        assertThat(scaled.mimeType()).isEqualTo("image/jpeg");
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(scaled.bytes()));
        assertThat(Math.max(result.getWidth(), result.getHeight())).isEqualTo(AwtSourceImageScaler.MAX_SIDE);
        assertThat(scaled.bytes().length).isLessThan(png.length);
    }

    @Test
    void aBigFormatImageIoCannotDecodePassesThrough() {
        byte[] opaque = new byte[AwtSourceImageScaler.DOWNSCALE_ABOVE_BYTES + 1];
        Arrays.fill(opaque, (byte) 7);
        SourceImage webp = new SourceImage(opaque, "image/webp");

        assertThat(scaler.fitForVision(webp)).isSameAs(webp);
    }

    @Test
    void aCorruptBigPngFailsInsteadOfBeingSentWhole() throws IOException {
        byte[] png = bigNoisePng(1200);
        byte[] truncated = Arrays.copyOf(png, png.length / 2);

        assertThatThrownBy(() -> scaler.fitForVision(new SourceImage(truncated, "image/png"))).isInstanceOf(ImageScalingException.class);
    }
}
