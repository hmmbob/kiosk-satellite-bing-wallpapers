// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.bing;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/** SDK callbacks enqueue work; network I/O never holds up the slideshow or KS. */
public final class BingPlugin implements KioskPlugin {
    static final String MODE="plugin:kiosk-satellite-bing-wallpapers:bing";
    private final Http http;
    private volatile boolean stopped=true;
    private volatile Config config;
    private PluginHost host;
    private final Texts texts=new Texts();
    private boolean storageProblem;
    private ScheduledExecutorService state;
    private ExecutorService io;
    // All remaining state belongs exclusively to the state executor, except cache (I/O executor).
    private Cache cache;
    private boolean persistent, initialized, loading, active, screenOn=true, viewKnown;
    private String view, lastDocument, pendingDocument;
    private ScheduledFuture<?> pendingPublication;
    private long nextPublication;
    private long stateEvent, screenEvent, nextRefresh, nextSlide;
    private int failures, position=-1;
    private List<Frame> frames=new ArrayList<>();
    private final List<Integer> bag=new ArrayList<>();
    private final Random random=new Random();
    private static final class Frame {
        final Bing.Photo photo; final byte[] bytes;
        Frame(Bing.Photo photo,byte[] bytes) { this.photo=photo; this.bytes=bytes; }
    }
    public BingPlugin() { this(new Http.Network()); }
    BingPlugin(Http http) { this.http=http; }

    @Override public void start(PluginHost host,Map<String,Object> settings) {
        this.host=host; this.config=new Config(settings); stopped=false;
        state=Executors.newSingleThreadScheduledExecutor(r -> daemon(r,"bing-slideshow"));
        io=Executors.newSingleThreadExecutor(r -> daemon(r,"bing-download"));
        post(() -> {
            publish(Slides.message(texts.get("loading")));
            try { texts.choices(host); }
            catch(RuntimeException|LinkageError error) { diagnostic("Setting choices unavailable",error); }
            try {
                host.subscribe("screensaver.state"); host.subscribe("screensaver.view"); host.subscribe("screen.state");
                long initialState=stateEvent, initialScreen=screenEvent;
                host.executeCommand("isScreensaverActive",Collections.emptyMap(),(ok,data,error) -> post(() -> {
                    if(ok && stateEvent==initialState) { active=Boolean.TRUE.equals(data); resetTimer(); }
                }));
                host.executeCommand("isScreenOn",Collections.emptyMap(),(ok,data,error) -> post(() -> {
                    if(ok && screenEvent==initialScreen) { screenOn=!Boolean.FALSE.equals(data); resetTimer(); }
                }));
            } catch(RuntimeException error) { diagnostic("State events unavailable",error); status(texts.get("stateError"),true); }
            Config initial=config;
            io.execute(() -> initialize(initial));
        });
        state.scheduleWithFixedDelay(() -> { if(!stopped) try { tick(); } catch(RuntimeException error) { diagnostic("Slideshow error",error); status(texts.get("slideshowError"),true); } },1,1,TimeUnit.SECONDS);
    }
    private static Thread daemon(Runnable task,String name) { Thread t=new Thread(task,name); t.setDaemon(true); return t; }
    private void post(Runnable task) {
        ScheduledExecutorService executor=state;
        if(stopped || executor==null) return;
        try { executor.execute(() -> { if(!stopped) task.run(); }); } catch(RejectedExecutionException ignored) { }
    }
    private void initialize(Config initial) {
        String warning=""; Cache.Snapshot snapshot=null; List<Frame> loaded=new ArrayList<>();
        try {
            // SDK 1 has no general writable-directory API. In KS 2026.10.31 the package directory
            // is private and writable; only packaged files/assets are verified. Cache is a sibling
            // of assets, never an asset. It survives restarts but can be removed by a plugin update.
            File anchor=new File(host.assetPath("cache-anchor.txt")).getCanonicalFile();
            cache=new Cache(new File(anchor.getParentFile().getParentFile(),"bing-runtime-cache"));
            persistent=true;
        } catch(IOException|RuntimeException error) {
            try { cache=new Cache(null); } catch(IOException impossible) { throw new IllegalStateException(impossible); }
            diagnostic("Disk cache unavailable",error); warning=texts.get("memory");
        }
        try {
            snapshot=cache.load(initial);
            if(snapshot!=null) loaded=readFrames(snapshot.photos);
            cache.prune(snapshot==null?Collections.emptyList():snapshot.photos);
        } catch(IOException error) {
            diagnostic("Cache recovery needed",error); warning=texts.get("storage");
            try { cache.commit(initial,Collections.emptyList(),0); }
            catch(IOException cleanup) { diagnostic("Cache cleanup failed",cleanup); storageProblem=true; warning=texts.get("storage"); }
        }
        final Cache.Snapshot saved=snapshot; final List<Frame> ready=loaded; final String note=warning;
        post(() -> {
            initialized=true;
            if(!ready.isEmpty()) install(ready);
            long age=saved==null?Long.MAX_VALUE:Math.max(0,System.currentTimeMillis()-saved.updated);
            boolean fresh=saved!=null && saved.source.equals(config.sourceKey()) && ready.size()==saved.photos.size()
                    && age<config.hours*3600000L;
            nextRefresh=fresh?now()+config.hours*3600000L-age:0;
            if(fresh && ready.isEmpty()) publish(Slides.message(texts.get("empty")));
            status(texts.ready(ready.size())+(note.isEmpty()?"":" "+note),!note.isEmpty());
            tick();
        });
    }
    @Override public void configure(Map<String,Object> settings) {
        Config next=new Config(settings);
        post(() -> {
            Config previous=config; config=next;
            if(!previous.sourceKey().equals(next.sourceKey()) || previous.hours!=next.hours) nextRefresh=0;
            bag.clear();
            if(position>=Math.min(frames.size(),next.count)) position=0;
            render(); resetTimer(); tick();
        });
    }
    @Override public void execute(String command,Map<String,Object> args) {
        if("next".equals(command)) post(() -> { advance(); resetTimer(); });
        else if("refresh".equals(command)) post(() -> { nextRefresh=0; tick(); });
        else throw new IllegalArgumentException("Unknown command: "+command);
    }
    @Override public void onEvent(String event,Map<String,Object> payload) {
        if(payload==null) return;
        Map<String,Object> copy=new HashMap<>(payload);
        post(() -> {
            if("settings.choices".equals(event)) {
                String run=String.valueOf(copy.get("run"));
                try { texts.choices(host); host.setupResult(run,"success",texts.get("choicesReady")); }
                catch(RuntimeException|LinkageError error) {
                    diagnostic("Setting choices refresh failed",error);
                    try { host.setupResult(run,"error",texts.get("choicesError")); }
                    catch(RuntimeException|LinkageError ignored) { }
                }
                return;
            }
            if("ks.screensaver.state".equals(event)) { stateEvent++; active=Boolean.TRUE.equals(copy.get("active")); }
            else if("ks.screensaver.view".equals(event)) { viewKnown=true; view=copy.get("view") instanceof String?(String)copy.get("view"):null; }
            else if("ks.screen.state".equals(event)) { screenEvent++; screenOn=!Boolean.FALSE.equals(copy.get("on")); }
            else return;
            resetTimer();
        });
    }
    private boolean visible() { return active && screenOn && (!viewKnown || MODE.equals(view)); }
    private static long now() { return System.nanoTime()/1000000; }
    private void resetTimer() { nextSlide=now()+config.interval*1000L; }
    private void tick() {
        if(stopped || !initialized) return;
        long time=now();
        if(visible() && frames.size()>1 && time>=nextSlide) { advance(); resetTimer(); }
        if(!loading && time>=nextRefresh) {
            loading=true;
            Config requested=config;
            io.execute(() -> refresh(requested));
        }
    }
    private List<Frame> readFrames(List<Bing.Photo> photos) throws IOException {
        List<Frame> result=new ArrayList<>();
        for(Bing.Photo p:photos) { byte[] bytes=cache.get(p); if(bytes!=null) result.add(new Frame(p,bytes)); }
        return result;
    }
    private void refresh(Config requested) {
        List<Frame> result=null; int wanted=0; String error=null;
        try {
            List<Bing.Photo> photos=Bing.parse(http.get(Bing.archiveUrl(requested),128*1024),requested);
            if(stopped) return;
            if(!requested.sourceKey().equals(config.sourceKey())) {
                post(() -> { loading=false; nextRefresh=0; tick(); }); return;
            }
            wanted=photos.size(); List<Bing.Photo> available=new ArrayList<>();
            // A valid feed is authoritative, even if every new JPEG subsequently fails.
            // Reconcile before downloading so old generations cannot consume disk indefinitely.
            post(() -> {
                if(requested.sourceKey().equals(config.sourceKey())) retainFeed(photos);
            });
            cache.commit(requested,photos,0);
            for(Bing.Photo p:photos) {
                if(stopped || !requested.sourceKey().equals(config.sourceKey())) break;
                try {
                    byte[] bytes;
                    try { bytes=cache.get(p); } catch(IOException damaged) { bytes=null; }
                    if(bytes==null) { bytes=Bing.download(http,p,requested); cache.put(p,bytes); }
                    available.add(p);
                } catch(IOException failed) { error=failed.getMessage(); }
            }
            if(stopped) return;
            if(!requested.sourceKey().equals(config.sourceKey())) {
                Cache.Snapshot committed=cache.load(requested);
                cache.prune(committed==null?Collections.emptyList():committed.photos);
                post(() -> { loading=false; nextRefresh=0; tick(); }); return;
            }
            result=readFrames(available);
            // Keep the full feed in the journal so an interrupted/partial refresh retries at restart.
            cache.commit(requested,photos,available.size()==wanted?System.currentTimeMillis():0);
        } catch(IOException|RuntimeException failed) { error=failed.getMessage(); }
        final List<Frame> ready=result; final int expected=wanted; final String problem=error;
        post(() -> {
            loading=false;
            if(!requested.sourceKey().equals(config.sourceKey())) { nextRefresh=0; tick(); return; }
            boolean success=ready!=null;
            if(success) install(ready);
            if(success && ready.size()==expected && problem==null) {
                failures=0; nextRefresh=now()+requested.hours*3600000L;
                status(texts.ready(ready.size())+(!persistent?" "+texts.get("memory"):storageProblem?" "+texts.get("storage"):""),!persistent || storageProblem);
                if(ready.isEmpty()) publish(Slides.message(texts.get("empty")));
            } else {
                long retry=Math.min(30,1L<<Math.min(failures++,5)); nextRefresh=now()+retry*60000;
                diagnostic("Refresh incomplete: "+problem,null);
                status(texts.get("retry",retry),true);
                if(frames.isEmpty()) publish(Slides.message(texts.get("unavailable")));
            }
        });
    }
    private void retainFeed(List<Bing.Photo> photos) {
        Map<String,Bing.Photo> keep=new HashMap<>();
        for(Bing.Photo photo:photos) keep.put(photo.key,photo);
        List<Frame> retained=new ArrayList<>();
        for(Frame frame:frames) if(keep.containsKey(frame.photo.key)) retained.add(new Frame(keep.get(frame.photo.key),frame.bytes));
        if(retained.size()!=frames.size()) install(retained);
    }
    private void install(List<Frame> updated) {
        String current=position>=0 && position<frames.size()?frames.get(position).photo.key:null;
        frames=new ArrayList<>(updated); position=frames.isEmpty()?-1:0; bag.clear();
        for(int i=0;i<Math.min(frames.size(),config.count);i++) if(frames.get(i).photo.key.equals(current)) position=i;
        if(frames.isEmpty()) publish(Slides.message(texts.get("waiting")));
        else render();
        resetTimer();
    }
    private void advance() {
        int count=Math.min(frames.size(),config.count);
        if(count==0) return;
        if(!config.shuffle) position=(position+1)%count;
        else {
            if(bag.isEmpty()) {
                for(int i=0;i<count;i++) if(i!=position) bag.add(i);
                Collections.shuffle(bag,random);
            }
            if(!bag.isEmpty()) position=bag.remove(bag.size()-1);
        }
        render();
    }
    private void render() {
        if(position<0 || position>=frames.size()) return;
        Frame f=frames.get(position); publish(Slides.document(f.photo,f.bytes,config));
    }
    private void publish(String html) {
        if(stopped) return;
        pendingDocument=html.replace("<html>","<html lang=\"en\">");
        if(pendingPublication!=null) return;
        flushPublication();
    }
    /** At most four calls in any second. Coalesce bursts and retry the latest desired page. */
    private void flushPublication() {
        pendingPublication=null;
        if(stopped || pendingDocument==null) return;
        if(pendingDocument.equals(lastDocument)) { pendingDocument=null; return; }
        long delay=nextPublication-now();
        if(delay>0) {
            pendingPublication=state.schedule(this::flushPublication,delay,TimeUnit.MILLISECONDS); return;
        }
        String html=pendingDocument;
        nextPublication=now()+300;
        try {
            host.publishScreensaver("bing",texts.get("mode"),html);
            lastDocument=html; pendingDocument=null;
        } catch(RuntimeException error) {
            diagnostic("Cannot publish screensaver",error); status(texts.get("publishError"),true);
            if(!stopped) pendingPublication=state.schedule(this::flushPublication,1000,TimeUnit.MILLISECONDS);
        }
    }
    private void diagnostic(String message,Throwable error) {
        if(stopped) return;
        try { host.log(message+(error==null?"":": "+error.getMessage())); } catch(RuntimeException ignored) { }
    }
    private void status(String text,boolean error) {
        if(stopped) return;
        try { host.status(text.length()>1000?text.substring(0,1000):text,error); } catch(RuntimeException ignored) { }
    }
    @Override public void stop() throws InterruptedException {
        stopped=true;
        if(state!=null) state.shutdownNow();
        if(io!=null) io.shutdownNow();
        http.abort();
        // Stay under the KS callback deadline. Revoked host calls are also guarded above.
        if(state!=null) state.awaitTermination(500,TimeUnit.MILLISECONDS);
        if(io!=null) io.awaitTermination(1500,TimeUnit.MILLISECONDS);
    }
}
