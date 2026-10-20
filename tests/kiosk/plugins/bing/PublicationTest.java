// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.bing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static kiosk.plugins.bing.PluginTest.*;

final class PublicationTest {
    static final class StrictHost extends Host {
        final Deque<Long> calls=new ArrayDeque<>();
        volatile int rejected;
        volatile boolean rejectOnce;
        StrictHost(Path path) throws IOException { super(path); }
        @Override public synchronized void publishScreensaver(String key,String title,String html) {
            long time=System.nanoTime();
            while(!calls.isEmpty() && time-calls.peekFirst()>=1000000000L) calls.removeFirst();
            if(calls.size()>=4) { rejected++; throw new IllegalStateException("Too many updates"); }
            calls.addLast(time);
            if(rejectOnce) { rejectOnce=false; throw new IllegalStateException("Temporary host failure"); }
            super.publishScreensaver(key,title,html);
        }
    }
    static void run(Path temp) throws Exception {
        StrictHost host=new StrictHost(temp.resolve("pacing")); BingPlugin plugin=new BingPlugin(new FakeHttp());
        plugin.start(host,settings("count",3));
        try {
            await(()->host.status.contains("3 photos available"),"publication test ready");
            plugin.onEvent("ks.screensaver.state",settings("active",false));
            for(int i=0;i<24;i++) {
                plugin.execute("next",settings());
                plugin.configure(settings("count",3,"fit",i%2==0?"Fill screen":"Whole image"));
            }
            plugin.configure(settings("count",3,"fit","Whole image","caption",true));
            await(()->host.last().contains("object-fit:contain") && host.last().contains("&lt;credit&gt;"),"burst applies final appearance");
            check(host.rejected==0,"bursts respect four-updates-per-second limit");
            host.rejectOnce=true;
            plugin.configure(settings("count",3,"fit","Fill screen"));
            await(()->host.last().contains("object-fit:cover") && !host.last().contains("&lt;credit&gt;"),"temporary publication failure retries latest page");
            check(host.rejected==0,"retry stays within host limit");
        } finally { plugin.stop(); }
    }
}
