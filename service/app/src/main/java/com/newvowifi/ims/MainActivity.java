package com.newvowifi.ims;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.newvowifi.ims.clash.*;
import com.newvowifi.ims.network.*;
import com.newvowifi.ims.policy.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MainActivity extends Activity {
  private static final int REQ_RECORD_AUDIO = 101;
  private static final int REQ_IMPORT_YAML = 102;
  RuntimeController rt;
  LinearLayout body, cards;
  TextView status, clashStatus, clashMessage;
  Switch billableSwitch, clashSwitch;
  EditText subUrlInput;
  Spinner nodeSpinner;
  boolean updating, updatingNodes, subUrlSeeded;
  Handler handler = new Handler();
  String signature = "", nodeSignature = "";
  final List<String> nodeNames = new ArrayList<>();
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
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(24, 40, 24, 32);
    scroll.setOnApplyWindowInsetsListener((view, insets) -> {
      android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
      body.setPadding(24 + bars.left, 40 + bars.top, 24 + bars.right, 32 + bars.bottom);
      return insets;
    });
    scroll.addView(body);
    setContentView(scroll);
    scroll.post(() -> {
      WindowInsetsController controller = scroll.getWindowInsetsController();
      if (controller != null)
        controller.setSystemBarsAppearance(
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
      if (updating) return;
      if (!on) {
        rt.setBillable(false);
        return;
      }
      new AlertDialog.Builder(this)
          .setTitle("启用通话与短信")
          .setMessage("开启后，系统拨号器和短信应用可通过此服务使用运营商业务，费用按套餐收取。开发验证期间保持关闭。")
          .setPositiveButton("启用",
              (d, w) -> {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                  requestPermissions(
                      new String[] {android.Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
                else
                  rt.setBillable(true);
              })
          .setNegativeButton("保持关闭", (d, w) -> billableSwitch.setChecked(false))
          .setOnCancelListener(d -> billableSwitch.setChecked(false))
          .show();
    });
    body.addView(billableSwitch);

    // --- Built-in Clash (wfctun0) Section ---
    TextView clashHeader = text("内置 Clash（" + ClashConfig.TUN_DEVICE + " · 仅代理 Wi-Fi Calling，不开启系统 VPN）", 18);
    clashHeader.setTextColor(Color.rgb(20, 50, 80));
    clashHeader.setPadding(0, 20, 0, 6);
    body.addView(clashHeader);

    clashSwitch = new Switch(this);
    clashSwitch.setText("启用内置 Clash 内核（" + ClashConfig.TUN_DEVICE + "）");
    clashSwitch.setChecked(rt.clashEnabled());
    clashSwitch.setOnCheckedChangeListener((v, on) -> {
      if (!updating) rt.setClashEnabled(on);
    });
    body.addView(clashSwitch);

    clashStatus = text("内置 Clash：正在读取…", 14);
    body.addView(clashStatus);

    subUrlInput = new EditText(this);
    subUrlInput.setHint("输入 Clash 订阅链接（http/https）");
    subUrlInput.setSingleLine(true);
    subUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    body.addView(subUrlInput);

    LinearLayout profileActions = new LinearLayout(this);
    profileActions.setOrientation(LinearLayout.HORIZONTAL);
    Button fetchBtn = new Button(this);
    fetchBtn.setText("拉取订阅");
    fetchBtn.setOnClickListener(v -> {
      String url = subUrlInput.getText().toString().trim();
      if (url.isEmpty()) {
        setClashMessage("请先输入订阅链接");
        return;
      }
      setClashMessage("正在拉取订阅…");
      rt.clashFetchSubscription(url, msg -> runOnUiThread(() -> {
        setClashMessage(msg);
        signature = "";
        nodeSignature = "";
        refresh();
      }));
    });
    Button importBtn = new Button(this);
    importBtn.setText("导入 YAML");
    importBtn.setOnClickListener(v -> {
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.setType("*/*");
      startActivityForResult(intent, REQ_IMPORT_YAML);
    });
    Button reloadBtn = new Button(this);
    reloadBtn.setText("重载本地");
    reloadBtn.setOnClickListener(v -> {
      setClashMessage("正在重载 /data/adb/newvowifi_ims/clash/profile.yaml…");
      rt.clashReloadLocal(msg -> runOnUiThread(() -> {
        setClashMessage(msg);
        nodeSignature = "";
        refresh();
      }));
    });
    profileActions.addView(fetchBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    profileActions.addView(importBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    profileActions.addView(reloadBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    body.addView(profileActions);

    body.addView(text("出口节点（全局代理 " + ClashConfig.TUN_DEVICE + " 流量）：", 14));
    nodeSpinner = new Spinner(this);
    nodeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      public void onNothingSelected(AdapterView<?> a) {}
      public void onItemSelected(AdapterView<?> a, View v, int index, long id) {
        if (updatingNodes || index < 0 || index >= nodeNames.size()) return;
        String chosen = nodeNames.get(index);
        if (chosen.isEmpty() || chosen.equals(rt.clash.currentNode)) return;
        setClashMessage("正在切换节点：" + chosen + "…");
        rt.clashSelectNode(chosen, msg -> runOnUiThread(() -> {
          setClashMessage(msg);
          refresh();
        }));
      }
    });
    body.addView(nodeSpinner);

    LinearLayout nodeActions = new LinearLayout(this);
    nodeActions.setOrientation(LinearLayout.HORIZONTAL);
    Button delayBtn = new Button(this);
    delayBtn.setText("测速 / 刷新节点");
    delayBtn.setOnClickListener(v -> {
      setClashMessage("正在测速…");
      rt.clashTestDelay(msg -> runOnUiThread(() -> {
        setClashMessage(msg);
        nodeSignature = "";
        refresh();
      }));
    });
    Button useBuiltinBtn = new Button(this);
    useBuiltinBtn.setText("海外卡使用 " + ClashConfig.TUN_DEVICE);
    useBuiltinBtn.setOnClickListener(v -> {
      rt.useBuiltinTunForForeignSims();
      signature = "";
      setClashMessage("已将海外卡出口设为 " + ClashConfig.TUN_DEVICE);
      refresh();
    });
    nodeActions.addView(delayBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    nodeActions.addView(useBuiltinBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    body.addView(nodeActions);

    clashMessage = text("本地配置目录：/data/adb/newvowifi_ims/clash/profile.yaml", 13);
    body.addView(clashMessage);

    Button refresh = new Button(this);
    refresh.setText("刷新 SIM 与全部网络接口");
    refresh.setOnClickListener(v -> {
      signature = "";
      nodeSignature = "";
      rt.kick();
    });
    body.addView(refresh);
    cards = new LinearLayout(this);
    cards.setOrientation(LinearLayout.VERTICAL);
    body.addView(cards);
  }
  void setClashMessage(String msg) {
    clashMessage.setText(msg);
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
    clashSwitch.setChecked(rt.clashEnabled());
    updating = false;

    StringBuilder line = new StringBuilder(rt.status);
    for (RuntimeController.Binding b : rt.bindings.values())
      line.append("\n卡槽 ").append(b.slot() + 1).append(" · ")
          .append(b.providerBound ? "独立 IMS · " + b.ims : "等待 Wi-Fi Calling 接管")
          .append(Boolean.TRUE.equals(rt.radioOff.get(b.slot())) ? " · 蜂窝已关闭" : " · 射频状态待确认");
    line.append(rt.billable() ? "\n本服务业务已启用" : "\n验证模式：本服务通话与短信发送关闭");
    status.setText(line);

    RuntimeController.ClashState cs = rt.clash;
    if (!subUrlSeeded && !cs.subscriptionUrl.isEmpty() && subUrlInput.getText().length() == 0) {
      subUrlSeeded = true;
      subUrlInput.setText(cs.subscriptionUrl);
    }
    StringBuilder csLine = new StringBuilder("内核状态：");
    if (!rt.clashEnabled()) csLine.append("已停用");
    else if (!cs.hasProfile) csLine.append("等待配置（请拉取订阅、导入 YAML 或写入 profile.yaml）");
    else if (cs.running && cs.tunUp) csLine.append("运行中 · ").append(ClashConfig.TUN_DEVICE).append(" 已就绪（非系统 VPN）");
    else if (cs.running) csLine.append("启动中 · 等待 ").append(ClashConfig.TUN_DEVICE);
    else csLine.append("已配置，待启动");
    if (!cs.currentNode.isEmpty()) csLine.append("\n当前节点：").append(cs.currentNode);
    else if (!cs.savedNode.isEmpty()) csLine.append("\n已保存节点：").append(cs.savedNode);
    if (!cs.lastError.isEmpty()) csLine.append("\n内核提示：").append(cs.lastError);
    clashStatus.setText(csLine);

    StringBuilder nSig = new StringBuilder(cs.currentNode).append('|');
    for (RuntimeController.ClashNode n : cs.nodes) nSig.append(n.label()).append(';');
    if (!nodeSignature.equals(nSig.toString())) {
      nodeSignature = nSig.toString();
      nodeNames.clear();
      List<String> labels = new ArrayList<>();
      if (cs.nodes.isEmpty()) {
        nodeNames.add("");
        labels.add(cs.running ? "暂无节点（请检查配置或刷新）" : "启用内置 Clash 后显示节点列表");
      } else {
        for (RuntimeController.ClashNode n : cs.nodes) {
          nodeNames.add(n.name);
          labels.add(n.label());
        }
      }
      updatingNodes = true;
      ArrayAdapter<String> nodeAdapter =
          new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
      nodeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
      nodeSpinner.setAdapter(nodeAdapter);
      int pos = Math.max(0, nodeNames.indexOf(cs.currentNode));
      nodeSpinner.setSelection(pos, false);
      updatingNodes = false;
    }

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
            + " 个。Wi-Fi Calling 只走所选 TUN（推荐内置 " + ClashConfig.TUN_DEVICE
            + "）；不可用时通话失败。关闭上方开关后才恢复蜂窝，接口选择保留。",
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
    if (code == REQ_RECORD_AUDIO) {
      boolean granted =
          results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
      rt.setBillable(granted);
      updating = true;
      billableSwitch.setChecked(granted);
      updating = false;
    }
  }
  public void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    if (req == REQ_IMPORT_YAML && result == RESULT_OK && data != null && data.getData() != null) {
      Uri uri = data.getData();
      setClashMessage("正在读取 YAML 文件…");
      rt.io.execute(() -> {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
          if (in == null) throw new IOException("OPEN_FAILED");
          ByteArrayOutputStream buf = new ByteArrayOutputStream();
          byte[] chunk = new byte[8192];
          int n;
          while ((n = in.read(chunk)) != -1) {
            if (buf.size() + n > 4 * 1024 * 1024) throw new IOException("CLASH_INPUT_TOO_LARGE");
            buf.write(chunk, 0, n);
          }
          String yaml = new String(buf.toByteArray(), StandardCharsets.UTF_8);
          rt.clashSaveProfile(yaml, msg -> runOnUiThread(() -> {
            setClashMessage(msg);
            signature = "";
            nodeSignature = "";
            refresh();
          }));
        } catch (Exception e) {
          runOnUiThread(() -> setClashMessage("读取文件失败：" + RuntimeController.safeCode(e)));
        }
      });
    }
  }
}
