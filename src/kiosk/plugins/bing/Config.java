// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.bing;

import java.util.Arrays;
import java.util.Map;

final class Config {
    static final String[] MARKETS = {"en-US", "en-GB", "nl-NL", "de-DE", "fr-FR", "en-AU", "en-CA", "ja-JP", "zh-CN"};
    final int count, interval, hours, width;
    final String market;
    final boolean contain, shuffle, caption;
    Config(Map<String, Object> s) {
        count = number(s.get("count"), 7, 1, 8);
        interval = number(s.get("interval"), 60, 5, 3600);
        hours = number(s.get("refreshHours"), 24, 1, 48);
        width = "1920".equals(s.get("resolution")) ? 1920 : 1280;
        market = Arrays.asList(MARKETS).contains(s.get("market")) ? (String)s.get("market") : "en-US";
        contain = "Whole image".equals(s.get("fit"));
        shuffle = "Shuffle".equals(s.get("order"));
        caption = Boolean.TRUE.equals(s.get("caption"));
    }
    String sourceKey() { return market + ":" + count + ":" + width; }
    private static int number(Object value, int fallback, int min, int max) {
        double n = value instanceof Number ? ((Number)value).doubleValue() : fallback;
        return Double.isNaN(n) || Double.isInfinite(n) ? fallback : (int)Math.max(min, Math.min(max, n));
    }
}
