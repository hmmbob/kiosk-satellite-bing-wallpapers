// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.bing;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static kiosk.plugins.bing.PluginTest.*;

final class TextsTest {
    static final class LocalHost extends Host {
        final Map<String,Map<String,String>> choices=new java.util.concurrent.ConcurrentHashMap<>();
        volatile String result="",diagnostic="";
        LocalHost(Path root) throws IOException { super(root); }
        public void log(String text) { diagnostic=text; }
        public void setSettingChoices(String key,String[] ids,String[] labels) {
            Map<String,String> options=new LinkedHashMap<>();
            for(int i=0;i<ids.length;i++) options.put(ids[i],labels[i]);
            choices.put(key,options);
        }
        public boolean setupResult(String run,String state,String message) { result=run+":"+state+":"+message; return true; }
    }
    static void run(Path temp) throws Exception {
        check(new Texts().ready(1).equals("1 photo available."),"English singular");
        check(new Texts().ready(2).equals("2 photos available."),"English plural");
        {
            Texts text=new Texts(); FakeHttp network=new FakeHttp(); network.blocked=true;
            LocalHost host=new LocalHost(temp.resolve("english-texts"));
            BingPlugin plugin=new BingPlugin(network);
            plugin.start(host,settings("count",3,"fit","Whole image","order","Shuffle","caption",true));
            try {
                await(()->host.last().contains(text.get("loading")),"English initial message before downloads finish");
                check(host.modeTitle.equals(text.get("mode")) && host.modeKey.equals("bing"),"mode retains stable key");
                check(host.last().contains("lang=\"en\""),"HTML language is English");
                await(()->host.choices.size()==3,"all labelled choices registered");
                check(host.choices.get("order").keySet().equals(new HashSet<>(Arrays.asList("Newest first","Shuffle"))),"order values retain old saved settings");
                check(host.choices.get("fit").get("Whole image").equals("Show whole photo"),"readable fit label keeps legacy value");
                check(host.choices.get("market").get("nl-NL").equals("Netherlands"),"readable country name");
                network.blocked=false;
                await(()->host.status.equals(text.ready(3)),"English ready status");
                await(()->host.last().contains("object-fit:contain"),"legacy setting still displays whole image");
                check(host.last().contains("Photo 0 &amp; &lt;credit&gt;"),"Bing caption preserved and escaped, not machine translated");
                plugin.onEvent("settings.choices",settings("key","fit","run","test-run"));
                await(()->host.result.equals("test-run:success:"+text.get("choicesReady")),"choice refresh completes in package language");
                network.offline=true;plugin.execute("refresh",settings());
                await(()->host.error && host.status.equals(text.get("retry",1)),"English retry status");
                check(!host.status.contains("Offline") && host.diagnostic.contains("Offline"),"technical cause goes to log, not user status");
            } finally {plugin.stop();}
        }
    }
}
