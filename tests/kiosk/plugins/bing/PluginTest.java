// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.bing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import me.jxl.kiosk.plugins.PluginHost;

public final class PluginTest {
    static int assertions;
    static void check(boolean ok,String message) { assertions++; if(!ok) throw new AssertionError(message); }
    static Map<String,Object> settings(Object... pairs) {
        Map<String,Object> m=new HashMap<>(); for(int i=0;i<pairs.length;i+=2) m.put((String)pairs[i],pairs[i+1]); return m;
    }
    static byte[] image(int marker,int size) {
        byte[] b=new byte[Math.max(size,10)]; b[0]=(byte)255; b[1]=(byte)216; b[2]=(byte)marker; b[b.length-2]=(byte)255; b[b.length-1]=(byte)217; return b;
    }
    static byte[] archive(int count) {
        StringBuilder s=new StringBuilder("{\"images\":[");
        for(int i=0;i<count;i++) {
            if(i>0) s.append(',');
            s.append("{\"urlbase\":\"/th?id=OHR.Photo").append(i).append("_EN123\",\"startdate\":\"20261019\",\"copyright\":\"Photo ").append(i).append(" & <credit>\"}");
        }
        return s.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }
    static void await(BooleanSupplier test,String message) throws Exception {
        long until=System.currentTimeMillis()+8000;
        while(!test.getAsBoolean() && System.currentTimeMillis()<until) Thread.sleep(20);
        check(test.getAsBoolean(),message);
    }
    static final class FakeHttp implements Http {
        final AtomicInteger archives=new AtomicInteger(),images=new AtomicInteger();
        volatile boolean offline,blocked,aborted,failImages;
        volatile byte[] feed=archive(3);
        @Override public byte[] get(String url,int max) throws IOException {
            if(url.contains("HPImageArchive")) {
                archives.incrementAndGet();
                while(blocked && !aborted) try { Thread.sleep(10); } catch(InterruptedException e) { throw new InterruptedIOException(); }
                if(offline || aborted) throw new IOException("Offline");
                return feed;
            }
            images.incrementAndGet(); if(offline || aborted || failImages) throw new IOException("Offline");
            return image(url.contains("Photo0")?1:url.contains("Photo1")?2:3,100);
        }
        @Override public void abort() { aborted=true; blocked=false; }
    }
    static class Host implements PluginHost {
        final Path root; final List<String> documents=new CopyOnWriteArrayList<>();
        final Set<String> subscriptions=ConcurrentHashMap.newKeySet();
        volatile String status="",modeTitle="",modeKey=""; volatile boolean error;
        Host(Path root) throws IOException { this.root=root; Files.createDirectories(root.resolve("assets")); Files.write(root.resolve("assets/cache-anchor.txt"),new byte[]{1}); }
        public void showWindow(String a,String b,String c) { throw new AssertionError("No overlay"); }
        public void hideWindow() {} public void log(String message) {}
        public String assetPath(String name) { return root.resolve("assets").resolve(name).toString(); }
        public void subscribe(String name) { subscriptions.add(name); }
        public void executeCommand(String name,Map<String,Object> args,CommandCallback callback) { callback.onResult(true,true,null); }
        public void publishScreensaver(String key,String title,String html) { check(html.getBytes(StandardCharsets.UTF_8).length<=Slides.LIMIT,"page within KS limit"); modeKey=key; modeTitle=title; documents.add(html); }
        public void status(String text,boolean error) { status=text; this.error=error; }
        String last() { return documents.isEmpty()?"":documents.get(documents.size()-1); }
    }
    static void unitTests(Path temp) throws Exception {
        Config defaults=new Config(settings());
        check(defaults.count==7 && defaults.interval==60 && defaults.hours==24,"defaults");
        Config clamped=new Config(settings("count",100,"interval",Double.NaN,"refreshHours",0,"market","evil.com","fit","Whole image"));
        check(clamped.count==8 && clamped.interval==60 && clamped.hours==1 && clamped.market.equals("en-US") && clamped.contain,"settings validation");
        List<Bing.Photo> photos=Bing.parse(archive(10),defaults);
        check(photos.size()==7,"archive bounded to requested count");
        check(Bing.archiveUrl(defaults).contains("n=7&mkt=en-US"),"direct archive URL");
        String duplicates=new String(archive(1),StandardCharsets.UTF_8).replace("]}",","+new String(archive(1),StandardCharsets.UTF_8).substring(11));
        check(Bing.parse(duplicates.getBytes(StandardCharsets.UTF_8),defaults).size()==1,"duplicate images skipped");
        check(Bing.parse("{\"images\":[]}".getBytes(StandardCharsets.UTF_8),defaults).isEmpty(),"explicit empty feed is authoritative");
        for(String invalid:new String[]{"{}","{","{\"images\":[{\"urlbase\":\"https://evil.com/x\",\"startdate\":\"20261019\"}]}"}) {
            boolean rejected=false; try { Bing.parse(invalid.getBytes(StandardCharsets.UTF_8),defaults); } catch(IOException e) { rejected=true; }
            check(rejected,"invalid archive rejected");
        }
        boolean deepRejected=false;
        try { Json.parse(String.join("",Collections.nCopies(100,"["))+"0"+String.join("",Collections.nCopies(100,"]"))); }
        catch(IllegalArgumentException e) { deepRejected=true; }
        check(deepRejected,"excessive JSON nesting rejected without overflowing stack");
        for(String bad:new String[]{"http://www.bing.com/x","https://evil.com/x","https://www.bing.com:444/x","https://user@www.bing.com/x","https://www.bing.com.evil.com/x"}) {
            boolean rejected=false; try { Http.Network.safeUrl(bad); } catch(IOException e) { rejected=true; }
            check(rejected,"untrusted network URL rejected");
        }
        AtomicInteger attempts=new AtomicInteger();
        byte[] small=Bing.download((url,max)->image(1,attempts.incrementAndGet()==1?400000:100),photos.get(0),defaults);
        check(attempts.get()==2 && small.length==100,"oversize image retries smaller resolution");
        boolean rejected=false; try { Bing.download((url,max)->"not a jpeg".getBytes(StandardCharsets.UTF_8),photos.get(0),defaults); } catch(IOException e) { rejected=true; }
        check(rejected,"HTML response not accepted as an image");
        for(int n=0;n<64;n++) { byte[] b=new byte[n]; new Random(n).nextBytes(b); check(Slides.base64(b).equals(Base64.getEncoder().encodeToString(b)),"base64 encoder"); }
        String html=Slides.document(photos.get(0),image(1,Bing.MAX_IMAGE),new Config(settings("caption",true,"fit","Whole image")));
        check(html.getBytes(StandardCharsets.UTF_8).length<Slides.LIMIT && html.contains("object-fit:contain") && html.contains("&lt;credit&gt;"),"large image and escaped caption");
        check(!html.contains("https:") && !html.contains("<script"),"renderer needs no network or scripts");
        Cache cache=new Cache(temp.resolve("cache").toFile());
        cache.commit(defaults,photos.subList(0,1),0);
        cache.put(photos.get(0),small); cache.commit(defaults,photos.subList(0,1),123);
        Cache reopened=new Cache(temp.resolve("cache").toFile());
        check(reopened.load(defaults).photos.size()==1 && reopened.load(defaults).updated==123,"offline cache survives recreation");
        cache.commit(defaults,photos.subList(1,2),0);
        cache.put(photos.get(1),image(2,100)); cache.commit(defaults,photos.subList(1,2),456);
        check(cache.get(photos.get(0))==null && cache.get(photos.get(1))!=null,"old cache images pruned after commit");
        Path index=temp.resolve("cache/index.bin"); Files.move(index,temp.resolve("cache/index.bin.bak"));
        check(cache.load(defaults).updated==456,"interrupted cache replacement recovers backup");
        Files.write(temp.resolve("cache/"+photos.get(1).key+".jpg"),new byte[]{1,2});
        check(cache.load(defaults).photos.isEmpty(),"damaged cached JPEG skipped");
    }
    static void configurationRace(Path temp) throws Exception {
        FakeHttp network=new FakeHttp(); network.blocked=true;
        Host host=new Host(temp.resolve("race")); BingPlugin plugin=new BingPlugin(network);
        plugin.start(host,settings("count",3));
        try {
            await(()->network.archives.get()==1,"initial refresh has started");
            plugin.configure(settings("count",1,"market","nl-NL"));
            Thread.sleep(100); network.blocked=false;
            await(()->host.status.contains("1 photo available"),"latest settings replace an in-flight request");
            check(network.images.get()==1,"obsolete request did not download unused images");
            check(new Cache(temp.resolve("race/bing-runtime-cache").toFile()).load(new Config(settings())).photos.size()==1,"only latest requested set committed");
        } finally { plugin.stop(); }
        Host fallback=new Host(temp.resolve("memory")) {
            @Override public String assetPath(String name) { throw new UnsupportedOperationException("No assetPath"); }
        };
        BingPlugin memory=new BingPlugin(new FakeHttp()); memory.start(fallback,settings("count",3));
        try { await(()->fallback.status.contains("Internet is needed again after a restart"),"missing writable cache has visible memory fallback"); }
        finally { memory.stop(); }
    }
    static void lifecycle(Path temp) throws Exception {
        Map<String,Object> s=settings("interval",5,"count",3);
        FakeHttp network=new FakeHttp(); Host host=new Host(temp.resolve("plugin")); BingPlugin plugin=new BingPlugin(network);
        long before=System.nanoTime(); plugin.start(host,s); check((System.nanoTime()-before)/1000000<500,"start is non-blocking");
        try {
            await(()->host.status.contains("3 photos available"),"all three images downloaded");
            await(()->host.last().contains("data:image/jpeg"),"first photo has actually been published");
            check("Slideshow".equals(host.modeTitle) && "bing".equals(host.modeKey),"distinct mode title preserves saved mode key");
            check(network.images.get()==3,"initial prefetch stores every photo");
            check(host.subscriptions.size()==3,"host state subscriptions");
            String first=host.last(); await(()->!host.last().equals(first),"timer advances without another download");
            check(network.images.get()==3,"slideshow uses local images");
            plugin.onEvent("ks.screen.state",settings("on",false)); Thread.sleep(200);
            String off=host.last(); Thread.sleep(5500); check(host.last().equals(off),"screen off pauses slideshow");
            network.offline=true; plugin.execute("refresh",settings());
            await(()->host.error && host.status.contains("Unable to update photos"),"offline refresh reports useful error");
            plugin.execute("next",settings()); await(()->!host.last().equals(off),"offline next photo uses cache");
            plugin.configure(settings("count",3,"interval",5,"fit","Whole image","caption",true));
            await(()->host.last().contains("object-fit:contain") && host.last().contains("&lt;credit&gt;"),"settings redraw current photo");
            plugin.onEvent("ks.screen.state",settings("on",true));
            plugin.onEvent("ks.screensaver.view",settings("view","clock")); Thread.sleep(200);
            String clock=host.last(); Thread.sleep(5500); check(host.last().equals(clock),"other screensaver mode pauses slideshow");
            plugin.onEvent("ks.screensaver.view",settings("view",BingPlugin.MODE));
            await(()->!host.last().equals(clock),"returning to Bing resumes after interval");
            network.offline=false; network.blocked=true; plugin.execute("refresh",settings());
            await(()->network.archives.get()>=3,"network request running");
            long stop=System.nanoTime(); plugin.stop(); check((System.nanoTime()-stop)/1000000<2500,"stop interrupts download within callback deadline");
            int count=host.documents.size(); Thread.sleep(300); check(host.documents.size()==count,"no publication after stop");
        } finally { plugin.stop(); }
        FakeHttp offline=new FakeHttp(); offline.offline=true; Host restarted=new Host(temp.resolve("plugin")); BingPlugin again=new BingPlugin(offline);
        again.start(restarted,s);
        try {
            await(()->restarted.last().contains("data:image/jpeg"),"offline restart displays persisted photo");
            String old=restarted.last(); again.execute("next",settings()); await(()->!restarted.last().equals(old),"offline restart retains multiple images");
        } finally { again.stop(); }
    }
    public static void main(String[] args) throws Exception {
        Path temp=Files.createTempDirectory("bing-test-cache-");
        try { unitTests(temp); lifecycle(temp); configurationRace(temp); CacheTest.run(temp); PublicationTest.run(temp); TextsTest.run(temp); System.out.println("PASS: "+assertions+" assertions (archive, bounds, rendering, disk cache, timing, offline, lifecycle, settings races, feed reconciliation, publication pacing, English texts)"); }
        finally { try(java.util.stream.Stream<Path> paths=Files.walk(temp)) { paths.sorted(Comparator.reverseOrder()).forEach(p->{try {Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}}); } }
    }
}
