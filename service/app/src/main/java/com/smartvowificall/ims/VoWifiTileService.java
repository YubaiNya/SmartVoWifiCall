package com.smartvowificall.ims;

import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings Tile showing live SmartVoWifiCall registration status with one-tap toggle.
 * Long-pressing the tile opens MainActivity via ACTION_QS_TILE_PREFERENCES.
 */
public final class VoWifiTileService extends TileService {
  @Override
  public void onStartListening() {
    super.onStartListening();
    updateTile();
  }

  @Override
  public void onClick() {
    super.onClick();
    RuntimeController rt = RuntimeController.get(this);
    boolean next = !rt.enabled();
    rt.setEnabled(next);
    updateTile();
  }

  private void updateTile() {
    Tile tile = getQsTile();
    if (tile == null) return;
    RuntimeController rt = RuntimeController.get(this);
    boolean enabled = rt.enabled();
    int registeredCount = 0;
    boolean anyConnecting = false;
    for (RuntimeController.Binding b : rt.bindings.values()) {
      if (b.registered) registeredCount++;
      else if ("CONNECTING".equals(b.tunnel) || "REGISTERING".equals(b.ims)) anyConnecting = true;
    }
    tile.setLabel("Wi-Fi 通话");
    tile.setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher));
    if (!enabled) {
      tile.setState(Tile.STATE_INACTIVE);
      if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("省电已关闭");
    } else if (registeredCount >= 2) {
      tile.setState(Tile.STATE_ACTIVE);
      if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("双卡已注册");
    } else if (registeredCount == 1) {
      tile.setState(Tile.STATE_ACTIVE);
      if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("已注册");
    } else {
      tile.setState(Tile.STATE_ACTIVE);
      if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(anyConnecting ? "连接中…" : "等待注册");
    }
    tile.updateTile();
  }
}
