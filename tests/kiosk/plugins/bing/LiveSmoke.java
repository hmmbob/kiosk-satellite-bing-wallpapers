// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.bing;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

/** Explicit network smoke test; never part of the deterministic offline suite. */
public final class LiveSmoke {
    public static void main(String[] args) throws Exception {
        Path out=Paths.get(args[0]); Files.createDirectories(out);
        Config c=new Config(Collections.<String,Object>singletonMap("count",8));
        Http http=new Http.Network();
        byte[] json=http.get(Bing.archiveUrl(c),128*1024);
        Files.write(out.resolve("archive.json"),json);
        List<Bing.Photo> photos=Bing.parse(json,c);
        long total=0; int maxPage=0; int i=0;
        for(Bing.Photo photo:photos) {
            byte[] jpeg=Bing.download(http,photo,c);
            java.awt.image.BufferedImage decoded=ImageIO.read(new ByteArrayInputStream(jpeg));
            if(decoded==null) throw new AssertionError("Cannot decode JPEG");
            String html=Slides.document(photo,jpeg,c);
            total+=jpeg.length; maxPage=Math.max(maxPage,html.getBytes(StandardCharsets.UTF_8).length);
            Files.write(out.resolve("slide-"+i+".html"),html.getBytes(StandardCharsets.UTF_8));
            System.out.println("Image "+(++i)+": "+decoded.getWidth()+"x"+decoded.getHeight()+", "+jpeg.length+" bytes");
        }
        if(photos.size()<2) throw new AssertionError("Expected multiple live images");
        System.out.println("LIVE PASS: "+i+" JPEGs decoded, "+total+" image bytes, largest page "+maxPage+" / "+Slides.LIMIT);
        http.abort();
    }
}
