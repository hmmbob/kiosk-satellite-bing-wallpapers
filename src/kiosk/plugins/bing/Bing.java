// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.bing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class Bing {
    static final int MAX_IMAGE = 370000;
    static final class Photo {
        final String base, date, caption, key;
        Photo(String base, String date, String caption, int width) {
            this.base = base; this.date = date; this.caption = caption;
            this.key = hash(base + ":" + width);
        }
    }
    static List<Photo> parse(byte[] json, Config c) throws IOException {
        try {
            Object root = Json.parse(new String(json, StandardCharsets.UTF_8));
            if (!(root instanceof Map)) throw new IllegalArgumentException("Invalid archive");
            Object images = ((Map<?,?>)root).get("images");
            if (!(images instanceof List)) throw new IllegalArgumentException("Missing images");
            List<Photo> photos = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (Object item : (List<?>)images) {
                if (!(item instanceof Map)) throw new IllegalArgumentException("Invalid image record");
                Map<?,?> row = (Map<?,?>)item;
                String base = string(row.get("urlbase")), date = string(row.get("startdate"));
                // Accept only Bing's image identifiers, not arbitrary response-provided hosts/paths.
                if (!base.matches("/th\\?id=OHR\\.[A-Za-z0-9_-]{1,220}") || !date.matches("[0-9]{8}"))
                    throw new IllegalArgumentException("Invalid Bing image identifier or date");
                if (!seen.add(base)) continue;
                String caption = string(row.get("copyright"));
                if (caption.length() > 600) caption = caption.substring(0, 600);
                photos.add(new Photo(base, date, caption, c.width));
                if (photos.size() == c.count) break;
            }
            // An explicit empty images array is authoritative; malformed responses throw above.
            return photos;
        } catch (IllegalArgumentException e) { throw new IOException("Cannot read Bing archive: " + e.getMessage(), e); }
    }
    static String archiveUrl(Config c) {
        return "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=" + c.count + "&mkt=" + c.market;
    }
    static byte[] download(Http http, Photo p, Config c) throws IOException {
        String[] sizes = c.width == 1920 ? new String[]{"1920x1080", "1280x720", "960x540", "800x480"}
                : new String[]{"1280x720", "960x540", "800x480"};
        IOException last = null;
        for (String size : sizes) {
            try {
                byte[] bytes = http.get("https://www.bing.com" + p.base + "_" + size + ".jpg", 4 * 1024 * 1024);
                if (!jpeg(bytes)) throw new IOException("Bing did not return a JPEG");
                if (bytes.length <= MAX_IMAGE) return bytes;
                last = new IOException("Image too large for KS");
            } catch (IOException error) {
                if (Thread.currentThread().isInterrupted()) throw error;
                last = error;
            }
        }
        throw last == null ? new IOException("Image download failed") : last;
    }
    static boolean jpeg(byte[] b) { return b != null && b.length > 4 && (b[0]&255)==255 && (b[1]&255)==216 && (b[b.length-2]&255)==255 && (b[b.length-1]&255)==217; }
    static String hash(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String string(Object o) { return o instanceof String ? (String)o : ""; }
}
