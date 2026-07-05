package com.resonance.meditation;

import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Bridge from the web timer to the native BellService. The WebView schedules
 * nothing itself once a sit is running — it just tells the service the plan
 * (which bell, interval, total) and the service rings on time with screen off.
 */
@CapacitorPlugin(name = "Bell")
public class BellPlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        Intent i = new Intent(getContext(), BellService.class);
        i.setAction(BellService.ACTION_START);
        i.putExtra(BellService.EXTRA_BELL, call.getString("bell", "bowl"));
        i.putExtra(BellService.EXTRA_INTERVAL, call.getInt("intervalSec", 0));
        i.putExtra(BellService.EXTRA_TOTAL, call.getInt("totalSec", 0));
        i.putExtra(BellService.EXTRA_ELAPSED, call.getInt("elapsedSec", 0));
        ContextCompat.startForegroundService(getContext(), i);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent i = new Intent(getContext(), BellService.class);
        i.setAction(BellService.ACTION_STOP);
        getContext().startService(i);
        call.resolve();
    }
}
