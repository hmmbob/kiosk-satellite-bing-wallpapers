// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.bing;

import java.nio.charset.StandardCharsets;

final class Slides {
    static final int LIMIT=512*1024;
    static String document(Bing.Photo p, byte[] bytes, Config c) {
        if (!Bing.jpeg(bytes) || bytes.length>Bing.MAX_IMAGE) throw new IllegalArgumentException("Invalid image");
        String html="<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            +"<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000}"
            +"img{display:block;width:100%;height:100%;object-fit:"+(c.contain?"contain":"cover")+"}"
            +"p{position:absolute;bottom:2vh;right:2vw;max-width:88vw;margin:0;padding:.4em .7em;border-radius:.3em;"
            +"background:rgba(0,0,0,.55);color:#fff;font:clamp(11px,2.2vmin,22px) sans-serif;text-align:right}</style></head>"
            +"<body><img alt='' src='data:image/jpeg;base64,"+base64(bytes)+"'>"
            +(c.caption?"<p>"+escape(p.caption)+"</p>":"")+"</body></html>";
        if(html.getBytes(StandardCharsets.UTF_8).length>LIMIT) throw new IllegalArgumentException("Screensaver document too large");
        return html;
    }
    static String message(String text) { return "<!doctype html><html><head><meta charset='utf-8'></head><body style='margin:0;background:#000;color:#bbb;font:20px sans-serif;display:flex;align-items:center;justify-content:center;height:100vh'><p>"+escape(text)+"</p></body></html>"; }
    static String escape(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
    private static final char[] ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    /** Compatible with Android 7; adapted from the Apache-2.0 Google Photos example. */
    static String base64(byte[] data) {
        char[] out=new char[(data.length+2)/3*4]; int o=0;
        for(int i=0;i<data.length;i+=3) {
            int b=(data[i]&255)<<16;
            if(i+1<data.length) b|=(data[i+1]&255)<<8;
            if(i+2<data.length) b|=data[i+2]&255;
            out[o++]=ALPHABET[b>>18&63]; out[o++]=ALPHABET[b>>12&63];
            out[o++]=i+1<data.length?ALPHABET[b>>6&63]:'='; out[o++]=i+2<data.length?ALPHABET[b&63]:'=';
        }
        return new String(out);
    }
}
