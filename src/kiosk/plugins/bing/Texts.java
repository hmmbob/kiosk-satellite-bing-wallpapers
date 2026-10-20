// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.bing;

import java.util.*;
import me.jxl.kiosk.plugins.PluginHost;

/** English messages and readable labels for stable stored settings values. */
final class Texts {
    private final Map<?,?> catalogue=(Map<?,?>)Json.parse(Catalogue.JSON);
    String get(String key,Object... values) {
        String text=(String)((Map<?,?>)catalogue.get("runtime")).get(key);
        if(text==null) throw new IllegalArgumentException("Unknown text: "+key);
        for(int i=0;i<values.length;i++) text=text.replace("{"+i+"}",String.valueOf(values[i]));
        return text;
    }
    String ready(int count) { return count==1?get("readyOne"):get("ready",count); }
    void choices(PluginHost host) {
        Map<?,?> settings=(Map<?,?>)catalogue.get("choices");
        for(Map.Entry<?,?> entry:settings.entrySet()) {
            Map<?,?> options=(Map<?,?>)entry.getValue();
            String[] ids=new String[options.size()],labels=new String[options.size()]; int i=0;
            for(Map.Entry<?,?> option:options.entrySet()) {
                ids[i]=(String)option.getKey();labels[i++]=(String)option.getValue();
            }
            host.setSettingChoices((String)entry.getKey(),ids,labels);
        }
    }
}
