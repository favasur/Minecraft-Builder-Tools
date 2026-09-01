import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Downscale a PNG into ASCII luminance art so the scene can be inspected in the terminal. */
public class AsciiView {
    static final String RAMP = " .:-=+*#%@";

    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        int cols = Integer.parseInt(args[1]);
        int rows = Integer.parseInt(args[2]);
        int cw = Math.max(1, img.getWidth() / cols);
        int ch = Math.max(1, img.getHeight() / rows);
        for (int r = 0; r < rows; r++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                long sum = 0;
                int n = 0;
                for (int y = r * ch; y < (r + 1) * ch && y < img.getHeight(); y++) {
                    for (int x = c * cw; x < (c + 1) * cw && x < img.getWidth(); x++) {
                        int argb = img.getRGB(x, y);
                        int a = (argb >>> 24) & 0xFF;
                        if (a < 100) continue;
                        int rr = (argb >> 16) & 0xFF;
                        int gg = (argb >> 8) & 0xFF;
                        int bb = argb & 0xFF;
                        sum += (rr * 299 + gg * 587 + bb * 114) / 1000;
                        n++;
                    }
                }
                sb.append(RAMP.charAt(n == 0 ? 0 : (int) ((sum / n) * (RAMP.length() - 1) / 255.0)));
            }
            System.out.println(sb);
        }
    }
}