package config

// CarrierHint describes known overseas carrier VoWiFi defaults for auto-tuning.
type CarrierHint struct {
	PLMN         string
	Name         string
	Region       string
	StrictHomeIP bool
	EPDGFQDN     string
}

var builtInCarrierHints = map[string]CarrierHint{
	"45403":  {PLMN: "45403", Name: "3HK", Region: "HK", StrictHomeIP: true, EPDGFQDN: "wlan.three.com.hk"},
	"45400":  {PLMN: "45400", Name: "CSL/ClubSIM", Region: "HK", StrictHomeIP: true, EPDGFQDN: "epdg.epc.mnc000.mcc454.pub.3gppnetwork.org"},
	"45419":  {PLMN: "45419", Name: "CSL/ClubSIM", Region: "HK", StrictHomeIP: true, EPDGFQDN: "epdg.epc.mnc019.mcc454.pub.3gppnetwork.org"},
	"45412":  {PLMN: "45412", Name: "CMHK", Region: "HK", StrictHomeIP: true, EPDGFQDN: "epdg.epc.mnc012.mcc454.pub.3gppnetwork.org"},
	"45406":  {PLMN: "45406", Name: "SmarTone", Region: "HK", StrictHomeIP: true, EPDGFQDN: "epdg.epc.mnc006.mcc454.pub.3gppnetwork.org"},
	"23420":  {PLMN: "23420", Name: "Three UK", Region: "UK", StrictHomeIP: true, EPDGFQDN: "epdg.epc.mnc020.mcc234.pub.3gppnetwork.org"},
	"23415":  {PLMN: "23415", Name: "Vodafone UK", Region: "UK", StrictHomeIP: true, EPDGFQDN: "epdg.epc.mnc015.mcc234.pub.3gppnetwork.org"},
	"23430":  {PLMN: "23430", Name: "EE UK", Region: "UK", StrictHomeIP: true, EPDGFQDN: "epdg.epc.mnc030.mcc234.pub.3gppnetwork.org"},
	"23410":  {PLMN: "23410", Name: "O2/Giffgaff", Region: "UK", StrictHomeIP: true, EPDGFQDN: "epdg.epc.mnc010.mcc234.pub.3gppnetwork.org"},
	"310260": {PLMN: "310260", Name: "T-Mobile US", Region: "US", StrictHomeIP: false, EPDGFQDN: "epdg.epc.mnc260.mcc310.pub.3gppnetwork.org"},
	"310240": {PLMN: "310240", Name: "Ultra/Mint US", Region: "US", StrictHomeIP: false, EPDGFQDN: "epdg.epc.mnc240.mcc310.pub.3gppnetwork.org"},
	"310410": {PLMN: "310410", Name: "AT&T US", Region: "US", StrictHomeIP: true, EPDGFQDN: "epdg.epc.att.net"},
	"311480": {PLMN: "311480", Name: "Verizon US", Region: "US", StrictHomeIP: true, EPDGFQDN: "wo.vzwwo.com"},
	"44010":  {PLMN: "44010", Name: "NTT Docomo", Region: "JP", StrictHomeIP: true, EPDGFQDN: "epdg.epc.mnc010.mcc440.pub.3gppnetwork.org"},
	"44011":  {PLMN: "44011", Name: "Rakuten Mobile", Region: "JP", StrictHomeIP: true, EPDGFQDN: "epdg.epc.mnc011.mcc440.pub.3gppnetwork.org"},
	"52501":  {PLMN: "52501", Name: "Singtel", Region: "SG", StrictHomeIP: true, EPDGFQDN: "epdg.epc.mnc001.mcc525.pub.3gppnetwork.org"},
	"50501":  {PLMN: "50501", Name: "Telstra", Region: "AU", StrictHomeIP: true, EPDGFQDN: "epdg.epc.mnc001.mcc505.pub.3gppnetwork.org"},
}

// LookupCarrierHint returns a known overseas carrier hint for MCC+MNC if present.
func LookupCarrierHint(mcc, mnc string) (CarrierHint, bool) {
	h, ok := builtInCarrierHints[mcc+mnc]
	return h, ok
}
