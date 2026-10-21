// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.bing;

import java.io.*;
import java.net.*;

interface Http {
    byte[] get(String url, int maxBytes) throws IOException;
    default void abort() {}

    final class Network implements Http {
        private volatile HttpURLConnection active;
        private volatile boolean closed;
        static URL safeUrl(String address) throws IOException {
            URL url = new URL(address);
            if (!"https".equals(url.getProtocol()) || !"www.bing.com".equalsIgnoreCase(url.getHost())
                    || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null)
                throw new IOException("Only HTTPS www.bing.com URLs are allowed");
            return url;
        }
        @Override public void abort() {
            closed = true;
            HttpURLConnection c = active;
            if (c != null) c.disconnect();
        }
        @Override public byte[] get(String address, int limit) throws IOException {
            for (int redirects = 0; redirects < 4; redirects++) {
                if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Stopped");
                URL url = safeUrl(address);
                HttpURLConnection c = (HttpURLConnection)url.openConnection();
                active = c;
                try {
                    if (closed) throw new InterruptedIOException("Stopped");
                    c.setConnectTimeout(10000); c.setReadTimeout(15000);
                    c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("User-Agent", "KioskSatellite-BingWallpapers (+https://github.com/hmmbob/kiosk-satellite-bing-wallpapers)");
                    c.setRequestProperty("Accept", "application/json,image/jpeg,*/*;q=0.5");
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String next = c.getHeaderField("Location");
                        if (next == null) throw new IOException("Redirect without location");
                        address = new URL(url, next).toString();
                        safeUrl(address);
                        continue;
                    }
                    if (code != 200) throw new IOException("Bing returned HTTP " + code);
                    if (c.getContentLengthLong() > limit) throw new IOException("Download exceeds size limit");
                    try (InputStream in = c.getInputStream()) { return read(in, limit); }
                } finally { active = null; c.disconnect(); }
            }
            throw new IOException("Too many redirects");
        }
    }
    static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Stopped");
            if (out.size() + n > limit) throw new IOException("Download exceeds size limit");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
