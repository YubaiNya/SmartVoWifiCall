package com.newvowifi.ims;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.newvowifi.ims.network.*;
import com.newvowifi.ims.policy.*;
import java.util.*;
public final class MainActivity extends Activity {
  RuntimeController rt;
  LinearLayout body, cards;
  TextView status;
  Switch billableSwitch;
  boolean updating;
  Handler handler = new Handler();
  String signature = "";
  final Runnable update = new Runnable() {
    public void run() {
      refresh();
      handler.postDelayed(this, 3000);
    }
  };
  public void onCreate(Bundle b) {
    super.onCreate(b);
    rt = RuntimeController.get(this);
    startForegroundService(new Intent(this, PolicyService.class));
    ScrollView scroll = new ScrollView(this);
    body = new LinearLayout(this);
    body.setOrientation(1);
    body.setPadding(24, 40, 24, 32);
    scroll.setOnApplyWindowInsetsListener((view,insets) -> {
      android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
      body.setPadding(24+bars.left,40+bars.top,24+bars.right,32+bars.bottom);
      return insets;
    });
    scroll.addView(body);
    setContentView(scroll);
    scroll.post(() -> {
      WindowInsetsController controller=scroll.getWindowInsetsController();
      if(controller!=null)controller.setSystemBarsAppearance(
          WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
          WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
    });
    TextView title = text("NewVoWiFi", 28);
    title.setTextColor(Color.rgb(20, 50, 80));
    body.addView(title);
    body.addView(text("海外 SIM 独立 IMS · 大陆 SIM 保留系统服务", 15));
    status = text("正在读取设备…", 16);
    body.addView(status);
    Switch enabled = new Switch(this);
    enabled.setText("海外卡 Wi-Fi Calling（关闭该卡蜂窝）");
    enabled.setChecked(rt.enabled());
    enabled.setOnCheckedChangeListener((v, on) -> rt.setEnabled(on));
    body.addView(enabled);
    billableSwitch = new Switch(this);
    billableSwitch.setText("允许通话和发送短信");
    billableSwitch.setChecked(rt.billable());
    billableSwitch.setOnCheckedChangeListener((v, on) -> {
      if (updating)
        return;
      if (!on) {
        rt.setBillable(false);
        return;
      }
      new AlertDialog.Builder(this)
          .setTitle("启用通话与短信")
          .setMessage("开启后，系统拨号器和短信应用可通过此服务使用运营商业务，费用按套餐收取。开发"
              + "验证期间保持关闭。")
          .setPositiveButton("启用",
              (d, w) -> {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                  requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, 101);
                else
                  rt.setBillable(true);
              })
          .setNegativeButton("保持关闭", (d, w) -> billableSwitch.setChecked(false))
          .setOnCancelListener(d -> billableSwitch.setChecked(false))
          .show();
    });
    body.addView(billableSwitch);
    Button refresh = new Button(this);
    refresh.setText("刷新 SIM 与全部网络接口");
    refresh.setOnClickListener(v -> {
      signature = "";
      rt.kick();
    });
    body.addView(refresh);
    cards = new LinearLayout(this);
    cards.setOrientation(1);
    body.addView(cards);
  }
  TextView text(String s, int size) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setPadding(0, 8, 0, 8);
    return t;
  }
  void refresh() {
    updating = true;
    billableSwitch.setChecked(rt.billable());
    updating = false;
    StringBuilder line = new StringBuilder(rt.status);
    for (RuntimeController.Binding b : rt.bindings.values())
      line.append("\n卡槽 ").append(b.slot() + 1).append(" · ")
          .append(b.providerBound ? "独立 IMS · " + b.ims : "等待 Wi-Fi Calling 接管")
          .append(Boolean.TRUE.equals(rt.radioOff.get(b.slot())) ? " · 蜂窝已关闭" : " · 射频状态待确认");
    line.append(rt.billable() ? "\n本服务业务已启用" : "\n验证模式：本服务通话与短信发送关闭");
    status.setText(line);
    if (rt.snapshot == null || rt.interfaces == null)
      return;
    StringBuilder key = new StringBuilder();
    for (RootSupervisor.Sim s : rt.snapshot.sims)
      key.append(s.policy.simKey).append(s.policy.subId).append(rt.selection(s.policy.simKey));
    for (InterfaceSnapshot.Entry e : rt.interfaces.entries.values()) key.append(e.label()).append(e.voWifiTun());
    if (signature.equals(key.toString()))
      return;
    signature = key.toString();
    cards.removeAllViews();
    for (RootSupervisor.Sim sim : rt.snapshot.sims) {
      boolean foreign = PolicyEngine.eligibility(sim.policy) == PolicyEngine.Reason.ELIGIBLE;
      cards.addView(text("卡槽 " + (sim.policy.slotId + 1) + " · subId " + sim.policy.subId + " · "
              + (foreign ? "海外归属卡"
                      : sim.policy.homePlmn.startsWith("460") || sim.policy.homePlmn.startsWith("461")
                      ? "大陆归属卡 · 系统 IMS"
                      : "SIM 信息待确认 · 系统 IMS"),
          18));
      if (!foreign)
        continue;
      List<String> names = new ArrayList<>(), labels = new ArrayList<>();
      names.add("");
      labels.add("请选择 VoWiFi 的 TUN 出口");
      String selected = rt.selection(sim.policy.simKey);
      for (InterfaceSnapshot.Entry e : rt.interfaces.entries.values()) {
        names.add(e.name);
        labels.add(e.label() + (e.voWifiTun() ? " · VoWiFi 出口" : " · 不用于 VoWiFi"));
      }
      if (!selected.isEmpty() && !names.contains(selected)) {
        names.add(selected);
        labels.add(selected + " · 已消失（保留选择）");
      }
      Spinner chooser = new Spinner(this);
      ArrayAdapter<String> adapter =
          new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
      adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
      chooser.setAdapter(adapter);
      chooser.setSelection(Math.max(0, names.indexOf(selected)));
      chooser.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        public void onNothingSelected(AdapterView<?> a) {}
        public void onItemSelected(AdapterView<?> a, View v, int index, long id) {
          String next = names.get(index);
          if (!next.equals(rt.selection(sim.policy.simKey)))
            rt.select(sim.policy.simKey, next);
        }
      });
      cards.addView(chooser);
    }
    cards.addView(text("系统接口：" + rt.interfaces.entries.size()
            + " 个。Wi-Fi Calling 只走所选 TUN；不可用时通话失败。关闭上方开关后才恢复蜂窝，接口选择保留。",
        14));
  }
  public void onResume() {
    super.onResume();
    handler.post(update);
  }
  public void onPause() {
    handler.removeCallbacks(update);
    super.onPause();
  }
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code == 101) {
      boolean granted =
          results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
      rt.setBillable(granted);
      updating = true;
      billableSwitch.setChecked(granted);
      updating = false;
    }
  }
}
