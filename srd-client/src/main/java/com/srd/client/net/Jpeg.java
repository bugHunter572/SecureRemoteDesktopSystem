package com.srd.client.net;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** JPEG encoding for the periodic full-desktop snapshot sent to the server. */
final class Jpeg {
    private Jpeg() {
    }

    static synchronized byte[] encode(BufferedImage img, float quality) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(Math.max(0.1f, Math.min(1f, quality)));
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
                w.setOutput(out);
                w.write(null, new IIOImage(img, null, null), p);
            }
            return bos.toByteArray();
        } finally {
            w.dispose();
        }
    }
}
