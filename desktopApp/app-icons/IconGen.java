import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Writes every platform's app icon from the one source artwork, desktopApp/app-icons/AutoPilot.svg (a 1024 × 1024
 * rounded square with a white "A" and pointer). Run from the repository root on a Mac, since the .icns uses iconutil:
 *
 *   java desktopApp/app-icons/IconGen.java
 *
 * The SVG must stay simple: one rounded rect (width, rx, fill) and one path (fill, M/L/Z commands only).
 */
public class IconGen {
    static final Path ICONS = Path.of("desktopApp/app-icons");
    static final Path RES = Path.of("androidApp/src/main/res");
    static Artwork art;

    public static void main(String[] args) throws Exception {
        art = Artwork.read(ICONS.resolve("AutoPilot.svg"));
        // iOS masks the corners itself, so its icon is the full square.
        write(square(1024), Path.of("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png"));
        write(desktop(512), ICONS.resolve("autopilot-icon-linux-512.png"));
        write(desktop(512), Path.of("shared/src/jvmMain/resources/autopilot-icon.png"));
        // In the app the mark sits in a title bar, so it fills its box (no margin).
        write(rounded(256, 0), Path.of("shared/src/commonMain/composeResources/drawable/autopilot_logo.png"));
        ico(Path.of("shared/src/jvmMain/resources/autopilot-icon.ico"));
        icns();
        // Android: a legacy launcher icon per density, and an adaptive icon (Android 8+) whose foreground is the
        // glyph alone on a 108 dp layer, sized so the artwork's square maps to the 72 dp visible area.
        String[][] densities = {{"mdpi", "48"}, {"hdpi", "72"}, {"xhdpi", "96"}, {"xxhdpi", "144"}, {"xxxhdpi", "192"}};
        for (String[] d : densities) {
            int px = Integer.parseInt(d[1]);
            write(rounded(px, 0), RES.resolve("mipmap-" + d[0] + "/ic_launcher.png"));
            write(foreground(px * 108 / 48), RES.resolve("mipmap-" + d[0] + "/ic_launcher_foreground.png"));
        }
        System.out.println("Icons regenerated.");
    }

    /** The source artwork: the background's size, corner radius and colour, and the white glyph. */
    record Artwork(double size, double radius, Color background, Color glyphColor, Path2D glyph) {
        static Artwork read(Path svg) throws IOException {
            String text = Files.readString(svg);
            String rect = match(text, "<rect[^>]*>");
            String path = match(text, "<path[^>]*>");
            double size = Double.parseDouble(attribute(rect, "width"));
            double radius = Double.parseDouble(attribute(rect, "rx"));
            Path2D glyph = new Path2D.Double();
            Matcher command = Pattern.compile("([MLZ])\\s*([-0-9.]+)?[ ,]*([-0-9.]+)?").matcher(attribute(path, "d"));
            while (command.find()) {
                switch (command.group(1)) {
                    case "M" -> glyph.moveTo(Double.parseDouble(command.group(2)), Double.parseDouble(command.group(3)));
                    case "L" -> glyph.lineTo(Double.parseDouble(command.group(2)), Double.parseDouble(command.group(3)));
                    default -> glyph.closePath();
                }
            }
            return new Artwork(size, radius, colour(attribute(rect, "fill")), colour(attribute(path, "fill")), glyph);
        }

        static String match(String text, String regex) {
            Matcher m = Pattern.compile(regex).matcher(text);
            if (!m.find()) throw new IllegalStateException("AutoPilot.svg has no " + regex);
            return m.group();
        }

        static String attribute(String element, String name) {
            Matcher m = Pattern.compile("\\s" + name + "=\"([^\"]*)\"").matcher(element);
            if (!m.find()) throw new IllegalStateException("AutoPilot.svg: missing " + name + " in " + element);
            return m.group(1);
        }

        static Color colour(String hex) {
            return new Color(Integer.parseInt(hex.substring(1), 16));
        }
    }

    static Graphics2D canvas(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** The artwork drawn into a box of `inner` pixels at `offset`, optionally with its background. */
    static void paint(Graphics2D g, double offset, double inner, boolean background, boolean roundedCorners) {
        AffineTransform saved = g.getTransform();
        g.translate(offset, offset);
        g.scale(inner / art.size(), inner / art.size());
        if (background) {
            g.setColor(art.background());
            if (roundedCorners) {
                g.fill(new RoundRectangle2D.Double(0, 0, art.size(), art.size(), art.radius() * 2, art.radius() * 2));
            } else {
                g.fillRect(0, 0, (int) art.size(), (int) art.size());
            }
        }
        g.setColor(art.glyphColor());
        g.fill(art.glyph());
        g.setTransform(saved);
    }

    /** Rounded artwork with a transparent margin of `margin` (a fraction of the side) on each edge. */
    static BufferedImage rounded(int size, double margin) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        paint(g, size * margin, size * (1 - 2 * margin), true, true);
        g.dispose();
        return image;
    }

    /** Desktop icons keep the transparent margin macOS, Windows and Linux icons expect (90 of 1024 on each edge). */
    static BufferedImage desktop(int size) {
        return rounded(size, 90.0 / 1024);
    }

    static BufferedImage square(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        paint(g, 0, size, true, false);
        g.dispose();
        return image;
    }

    /** Android's adaptive foreground: the glyph alone, the artwork's square scaled to the middle 72 of 108 dp. */
    static BufferedImage foreground(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        paint(g, size * 18.0 / 108, size * 72.0 / 108, false, false);
        g.dispose();
        return image;
    }

    static void write(BufferedImage image, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
    }

    static void icns() throws Exception {
        Path set = Files.createTempDirectory("autopilot").resolve("AutoPilot.iconset");
        Files.createDirectories(set);
        for (int size : new int[] {16, 32, 128, 256, 512}) {
            write(desktop(size), set.resolve("icon_" + size + "x" + size + ".png"));
            write(desktop(size * 2), set.resolve("icon_" + size + "x" + size + "@2x.png"));
        }
        Process process = new ProcessBuilder("iconutil", "-c", "icns", set.toString(), "-o", ICONS.resolve("AutoPilot.icns").toString())
            .inheritIO().start();
        if (process.waitFor() != 0) throw new IllegalStateException("iconutil failed");
    }

    /** An ICO whose entries are PNGs, which every supported Windows version reads. */
    static void ico(Path path) throws IOException {
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        List<byte[]> images = new ArrayList<>();
        for (int size : sizes) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(desktop(size), "png", png);
            images.add(png.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeShort(0);
        data.writeShort(Short.reverseBytes((short) 1));
        data.writeShort(Short.reverseBytes((short) sizes.length));
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            data.writeByte(sizes[i] == 256 ? 0 : sizes[i]);
            data.writeByte(sizes[i] == 256 ? 0 : sizes[i]);
            data.writeByte(0);
            data.writeByte(0);
            data.writeShort(Short.reverseBytes((short) 1));
            data.writeShort(Short.reverseBytes((short) 32));
            data.writeInt(Integer.reverseBytes(images.get(i).length));
            data.writeInt(Integer.reverseBytes(offset));
            offset += images.get(i).length;
        }
        for (byte[] image : images) data.write(image);
        Files.createDirectories(path.getParent());
        Files.write(path, out.toByteArray());
    }
}
