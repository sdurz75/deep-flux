package org.dual.hexa.ai.llm.adapter.out.image;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

import org.dual.hexa.ai.llm.domain.ImageScalingException;
import org.dual.hexa.ai.llm.port.out.ISourceImageScaler;
import org.dual.hexa.core.storage.domain.SourceImage;
import org.springframework.stereotype.Component;

/**
 * {@link ISourceImageScaler} con {@code javax.imageio}: oltre {@value #DOWNSCALE_ABOVE_BYTES} byte porta il lato lungo a
 * {@value #MAX_SIDE} px (JPEG). Un formato che ImageIO non decodifica (webp) passa invariato; un png/jpeg corrotto e' un errore.
 */
@Component
class AwtSourceImageScaler implements ISourceImageScaler {

    static final int DOWNSCALE_ABOVE_BYTES = 1_500_000;
    static final int MAX_SIDE = 1024;

    @Override
    public SourceImage fitForVision(SourceImage image) {
        if (image.bytes().length <= DOWNSCALE_ABOVE_BYTES) {
            return image;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image.bytes()));
            if (source == null || Math.max(source.getWidth(), source.getHeight()) <= MAX_SIDE) {
                return image;
            }
            double scale = (double) MAX_SIDE / Math.max(source.getWidth(), source.getHeight());
            BufferedImage scaled = new BufferedImage((int) (source.getWidth() * scale), (int) (source.getHeight() * scale),
                    BufferedImage.TYPE_INT_RGB);
            var g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(scaled, "jpg", out);
            return new SourceImage(out.toByteArray(), "image/jpeg");
        } catch (IOException | RuntimeException e) {
            throw new ImageScalingException("Impossibile ridurre l'immagine sorgente: " + e.getMessage(), e);
        }
    }
}
