// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.bing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static kiosk.plugins.bing.PluginTest.*;

final class CacheTest {
    static List<Bing.Photo> feed(int cycle, int count) {
        List<Bing.Photo> result=new ArrayList<>();
        for(int i=0;i<count;i++) result.add(new Bing.Photo("/th?id=OHR.Cycle"+cycle+"Photo"+i,"20261019","Image "+i,1280));
        return result;
    }
    static long size(Path root) throws IOException {
        try(java.util.stream.Stream<Path> files=Files.list(root)) {
            return files.mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new UncheckedIOException(e);}}).sum();
        }
    }
    static long jpgCount(Path root) throws IOException {
        try(java.util.stream.Stream<Path> files=Files.list(root)) { return files.filter(p->p.toString().endsWith(".jpg")).count(); }
    }
    static void run(Path temp) throws Exception {
        Config c=new Config(settings("count",8));
        Path path=temp.resolve("bounded-cache"); Cache cache=new Cache(path.toFile());
        List<Bing.Photo> previous=Collections.emptyList();
        // Many changing feeds and incomplete refreshes must not grow the file count.
        for(int cycle=0;cycle<32;cycle++) {
            List<Bing.Photo> current=feed(cycle,8);
            Files.write(path.resolve("index.bin.tmp"),new byte[20]);
            Files.write(path.resolve("index.bin.bak"),new byte[20]);
            if(!previous.isEmpty()) Files.write(path.resolve(previous.get(0).key+".jpg.bak"),new byte[20]);
            cache.commit(c,current,0);
            check(jpgCount(path)==0,"obsolete feed purged before new downloads, cycle "+cycle);
            int downloaded=cycle%9; // Includes all-download-failed and full sets.
            for(int i=0;i<downloaded;i++) cache.put(current.get(i),image(i,100));
            cache.commit(c,current,downloaded==8?123:0);
            check(jpgCount(path)==downloaded && size(path)<=Cache.MAX_STORAGE_BYTES,"cache bounded across partial refreshes");
            try(java.util.stream.Stream<Path> files=Files.list(path)) {
                check(files.noneMatch(p->p.toString().endsWith(".tmp")||p.toString().endsWith(".bak")),"interrupted writes cleaned");
            }
            previous=current;
        }
        List<Bing.Photo> current=feed(100,8); cache.commit(c,current,0);
        for(Bing.Photo p:current) cache.put(p,image(1,Bing.MAX_IMAGE));
        check(jpgCount(path)==8 && size(path)<Cache.MAX_STORAGE_BYTES,"eight largest images fit the hard cap");
        cache.put(current.get(0),image(2,Bing.MAX_IMAGE));
        check(size(path)<Cache.MAX_STORAGE_BYTES,"atomic replacement fits without accumulating backups");
        boolean refused=false;
        try { cache.put(feed(101,1).get(0),image(1,100)); } catch(IOException e) { refused=true; }
        check(refused,"cannot write a ninth or out-of-feed image");
        // A real empty feed removes every image, and restart cannot resurrect an old one.
        cache.commit(c,Collections.emptyList(),123);
        check(jpgCount(path)==0 && new Cache(path.toFile()).load(c).photos.isEmpty(),"empty feed deletes cached images persistently");
        // Incomplete or oversized on-disk journals are cleaned during startup recovery.
        Files.write(path.resolve("index.bin"),new byte[Cache.MAX_INDEX+1]);
        Files.write(path.resolve(current.get(0).key+".jpg"),image(1,100));
        cache.commit(c,Collections.emptyList(),0);
        check(jpgCount(path)==0 && Files.size(path.resolve("index.bin"))<=Cache.MAX_INDEX,"oversized index and orphan image cleaned");
        // If cleanup cannot run, stop accepting new images instead of silently growing.
        cache.commit(c,current,0);
        Path obstruction=path.resolve(current.get(0).key+".jpg");
        Files.createDirectory(obstruction); Files.write(obstruction.resolve("lock"),new byte[]{1});
        refused=false;
        try { cache.commit(c,Collections.emptyList(),0); } catch(IOException e) { refused=true; }
        check(refused,"cleanup failure is surfaced");
        refused=false;
        try { cache.put(current.get(1),image(1,100)); } catch(IOException e) { refused=true; }
        check(refused,"cleanup failure disables additional cache writes");
        Files.delete(obstruction.resolve("lock")); Files.delete(obstruction);
        cache.commit(c,current,0);
        Files.write(path.resolve("unexpected.dat"),new byte[(int)Cache.MAX_STORAGE_BYTES]);
        refused=false;
        try { cache.put(current.get(0),image(1,100)); } catch(IOException e) { refused=true; }
        check(refused,"absolute disk byte limit blocks writes even with unknown files");
        Files.delete(path.resolve("unexpected.dat"));
        Cache memory=new Cache(null); memory.commit(c,current,0); memory.put(current.get(0),image(1,100));
        memory.commit(c,Collections.emptyList(),0);
        check(memory.get(current.get(0))==null,"memory fallback also removes obsolete images");
        integration(temp);
    }
    static void integration(Path temp) throws Exception {
        FakeHttp network=new FakeHttp(); Host host=new Host(temp.resolve("feed-plugin"));
        BingPlugin plugin=new BingPlugin(network); Path cache=host.root.resolve("bing-runtime-cache");
        plugin.start(host,settings("count",3));
        try {
            await(()->host.status.contains("3 photos available"),"initial feed available");
            await(()->host.last().contains("data:image/jpeg"),"initial photo displayed");
            plugin.onEvent("ks.screensaver.state",settings("active",false));
            String old=host.last();
            network.feed="invalid json".getBytes(StandardCharsets.UTF_8);
            plugin.execute("refresh",settings());
            await(()->host.error && host.status.contains("Unable to update photos"),"malformed feed reported");
            check(jpgCount(cache)==3 && host.last().equals(old),"malformed feed preserves last good cache and display");
            int downloads=network.images.get();
            network.feed=archive(1); plugin.execute("refresh",settings());
            await(()->host.status.contains("1 photo available"),"smaller feed processed");
            check(jpgCount(cache)==1 && network.images.get()==downloads,"shrinking feed deletes obsolete files and reuses survivor");
            // A valid replacement feed removes old photos even when ALL downloads fail.
            network.feed=new String(archive(3),StandardCharsets.UTF_8).replace("OHR.Photo","OHR.Replacement").getBytes(StandardCharsets.UTF_8);
            network.failImages=true; plugin.execute("refresh",settings());
            await(()->host.status.contains("Unable to update photos"),"all new image failures reported");
            check(jpgCount(cache)==0,"disappeared files deleted despite failed replacements");
            await(()->!host.last().contains("data:image/jpeg"),"old-feed photo also removed from renderer");
            network.failImages=false; plugin.execute("refresh",settings());
            await(()->host.status.contains("3 photos available"),"new feed fills after connection recovers");
            network.feed="{\"images\":[]}".getBytes(StandardCharsets.UTF_8); plugin.execute("refresh",settings());
            await(()->host.status.contains("0 photos available"),"valid empty feed is processed");
            check(jpgCount(cache)==0,"valid empty feed removes all disk photos");
            await(()->host.last().contains("No photos available"),"empty feed has a clear screen message");
        } finally { plugin.stop(); }
        FakeHttp offline=new FakeHttp(); offline.offline=true;
        Host restarted=new Host(host.root); BingPlugin again=new BingPlugin(offline);
        again.start(restarted,settings("count",3));
        try {
            await(()->restarted.last().contains("No photos available"),"fresh empty feed shows correct message after offline restart");
            check(jpgCount(cache)==0,"offline restart does not resurrect removed feed images");
        } finally { again.stop(); }
    }
}
