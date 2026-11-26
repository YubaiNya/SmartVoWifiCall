package com.smartvowificall.ims.policy;

import com.smartvowificall.ims.engine.JsonCodec;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in & updatable Carrier + OEM compatibility profile registry derived from telemetry v2
 * observations (homePlmn, carrierName, ePDG FQDN, strict home-IP checks, PANI policy, OEM quirks).
 */
public final class CarrierProfiles {
  public static final class Carrier {
    public final String plmn;
    public final String name;
    public final String region;
    public final boolean strictHomeIp;
    public final String epdgFqdn;
    public final String paniPolicy;
    public final String note;

    public Carrier(String plmn, String name, String region, boolean strictHomeIp,
        String epdgFqdn, String paniPolicy, String note) {
      this.plmn = plmn;
      this.name = name;
      this.region = region;
      this.strictHomeIp = strictHomeIp;
      this.epdgFqdn = epdgFqdn;
      this.paniPolicy = paniPolicy;
      this.note = note;
    }

    public String uiSummary() {
      StringBuilder sb = new StringBuilder();
      sb.append(name);
      if (!region.isEmpty()) sb.append(" · 推荐").append(region).append("节点");
      if (strictHomeIp) sb.append(" · 校验归属地 IP");
      return sb.toString();
    }
  }

  public static final class OemQuirk {
    public final String manufacturer;
    public final String radioPolicy;
    public final String imsOverride;
    public final String note;

    public OemQuirk(String manufacturer, String radioPolicy, String imsOverride, String note) {
      this.manufacturer = manufacturer;
      this.radioPolicy = radioPolicy;
      this.imsOverride = imsOverride;
      this.note = note;
    }
  }

  private final Map<String, Carrier> carriers;
  private final Map<String, OemQuirk> oemQuirks;

  private CarrierProfiles(Map<String, Carrier> carriers, Map<String, OemQuirk> oemQuirks) {
    this.carriers = Collections.unmodifiableMap(carriers);
    this.oemQuirks = Collections.unmodifiableMap(oemQuirks);
  }

  public static CarrierProfiles parseJson(String json) throws IOException {
    return parseAndMerge(json, null);
  }

  /** Parses base JSON and optionally overlays user-updated JSON rules on top. */
  public static CarrierProfiles parseAndMerge(String baseJson, String overlayJson) throws IOException {
    Map<String, Carrier> carrierMap = new LinkedHashMap<>();
    Map<String, OemQuirk> oemMap = new LinkedHashMap<>();
    if (baseJson != null && !baseJson.trim().isEmpty()) {
      mergeInto(baseJson, carrierMap, oemMap);
    }
    if (overlayJson != null && !overlayJson.trim().isEmpty()) {
      mergeInto(overlayJson, carrierMap, oemMap);
    }
    return new CarrierProfiles(carrierMap, oemMap);
  }

  @SuppressWarnings("unchecked")
  private static void mergeInto(
      String json, Map<String, Carrier> carrierMap, Map<String, OemQuirk> oemMap)
      throws IOException {
    Map<String, Object> root = JsonCodec.decodeObject(json);
    Object rawCarriers = root.get("carriers");
    if (rawCarriers instanceof List<?>) {
      for (Object item : (List<?>) rawCarriers) {
        if (!(item instanceof Map<?, ?>)) continue;
        Map<String, Object> m = (Map<String, Object>) item;
        String plmn = str(m.get("plmn")).trim();
        if (!PolicyEngine.validHomePlmn(plmn)) continue;
        carrierMap.put(plmn, new Carrier(
            plmn,
            str(m.get("name")),
            str(m.get("region")),
            Boolean.TRUE.equals(m.get("strictHomeIp")),
            str(m.get("epdgFqdn")),
            str(m.get("paniPolicy")).isEmpty() ? "sanitize" : str(m.get("paniPolicy")),
            str(m.get("note"))));
      }
    }
    Object rawOems = root.get("oemQuirks");
    if (rawOems instanceof List<?>) {
      for (Object item : (List<?>) rawOems) {
        if (!(item instanceof Map<?, ?>)) continue;
        Map<String, Object> m = (Map<String, Object>) item;
        String mfr = str(m.get("manufacturer")).trim().toLowerCase(Locale.ROOT);
        if (mfr.isEmpty()) continue;
        oemMap.put(mfr, new OemQuirk(
            mfr,
            str(m.get("radioPolicy")),
            str(m.get("imsOverride")),
            str(m.get("note"))));
      }
    }
  }

  public Carrier findByPlmn(String homePlmn) {
    if (homePlmn == null) return null;
    return carriers.get(homePlmn.trim());
  }

  public OemQuirk findOem(String manufacturer) {
    if (manufacturer == null) return null;
    String key = manufacturer.trim().toLowerCase(Locale.ROOT);
    OemQuirk exact = oemQuirks.get(key);
    if (exact != null) return exact;
    for (Map.Entry<String, OemQuirk> e : oemQuirks.entrySet()) {
      if (key.contains(e.getKey())) return e.getValue();
    }
    return null;
  }

  public int carrierCount() { return carriers.size(); }
  public int oemCount() { return oemQuirks.size(); }

  private static String str(Object o) {
    return o instanceof String ? (String) o : "";
  }
}
