package com.newvowifi.ims;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
  public void onReceive(Context c, Intent i) {
    c.startForegroundService(new Intent(c, PolicyService.class));
  }
}
