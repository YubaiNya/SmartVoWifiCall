# CTExcel registration repair — 0.6.3-dev

## Evidence and changes

The affected line is CTExcel UK, PLMN 23433, on Xiaomi 15 / Android 17. The selected existing UK proxy was retained. Tests were registration-only; no calls or SMS were sent.

1. **ePDG selection was correct.** Both clients used `epdg.epc.mnc033.mcc234.pub.3gppnetwork.org`. SoftVoWifi's historical successful trace allocated IPv6 for the IMS PDN and IPv6 P-CSCF addresses. Its current device UI also successfully registered CTExcel during the comparison. The IMS identity configuration matched between the two clients.
2. **The built-in proxy rewrote UDP reply sources.** A bounded IKE_SA_INIT probe reached the selected ePDG through both UDP 500 and 4500. Through the existing `tun0` path it received valid replies; through `wfctun0`, packet capture and proxy counters showed replies arriving with a proxy-side source address/port. The connected UDP socket discarded those packets. Built-in isolated proxy transports now use an unconnected receiving socket while continuing to send to the selected ePDG. Incoming IKE packets must match the session SPIs, version and length; IKE integrity, EAP MAC, final responder AUTH and ESP authentication remain enforced.
3. **The IMS PDN needed IPv6.** CTExcel now requests IPv6 configuration and traffic selectors. The engine handles IPv6 configuration replies, matching P-CSCF family, inner IPv6 UDP/TCP, checksums, SIP address brackets and SDP/RTP address preservation. This is independent of the outer IPv4 connection to the ePDG.
4. **IMS security negotiation was missing.** The old first REGISTER elicited an ordinary MD5 challenge, which was incorrectly treated as an AKA nonce. Adding the AKA algorithm alone did not change that response. After advertising the implemented `ipsec-3gpp` mechanism, the live server returned AKAv1-MD5 and Security-Server parameters. CTExcel now negotiates protected client/server ports and SPI pairs, installs transport-mode ESP with HMAC-SHA1-96 and NULL encryption inside the encrypted ePDG tunnel, and echoes Security-Verify. Unsupported mechanisms and unprotected success are rejected. Ordinary MD5 is not treated as AKA.
5. **The authenticated REGISTER exceeded the proxy TUN MTU.** Capture showed the initial packet at 1300 bytes and the next request fragmented into 1396-byte and 212-byte IP packets, followed by timeout. REGISTER now uses RFC 3261 compact header names, a random opaque Call-ID, and omits optional Allow/User-Agent headers. Authentication and security-agreement fields remain intact. The resulting registration completed with 200 OK.

SIP security parameters are included in sanitized authentication traces; bare IPv6 addresses in Via/Call-ID and bracketed SIP addresses are masked. Unknown security parameters remain masked. ESP rejection reasons are reported without packet/key dumps.

## Validation

- Go suite and `go vet ./...`: passed.
- Proxy reply-source regression: a reply from a different UDP source is accepted only for matching IKE SPIs; foreign SPIs are discarded.
- IPv6 configuration/selector parsing and malformed attributes: passed.
- IPv6 UDP over ESP with an independent pseudo-header checksum check: passed.
- IMS transport ESP wire format, integrity rejection and replay rejection: passed.
- Local IPv6 P-CSCF registration tests cover both ordinary SIP and negotiated IMS ESP; the actual doubly encapsulated REGISTER datagrams must stay within 1400 bytes using full-length IPv6 addresses and IMS identities.
- IPv6 trace redaction, Java policy/telemetry checks and APK/module artifact validation: passed.
- First live protected CTExcel REGISTER 200: **2026-11-26 02:36:09 Asia/Shanghai**. Normal application stop/start repeated success at **02:37:44**, with UI reporting tunnel connected, IMS registered and the controlled slot's cellular radio off.
- Final packaged APK/module 0.6.3-dev / 23 was installed and the live engine hash matched the packaged binary. A fresh start registered at **2026-11-26 02:43:33 Asia/Shanghai**. Final capture measured the initial and authenticated REGISTER outer IP packets at **1092 and 1380 bytes**, respectively, with zero IP fragments.
- Final read-only engine RPC reported `ims=REGISTERED`, `tunnel=CONNECTED`, `uptimeSec=380`, confirming more than six minutes of continuous registration after the final packaged-engine restart.
- The existing built-in proxy used cellular underlay `rmnet_data3` during this test. A Wi-Fi icon alone is not evidence of the physical transport used.

The APK/module version is 0.6.3-dev / 23. This change does not establish a fix for the separate POCO F3 / giffgaff case, and does not establish voice/SMS operation.

## References

- [RFC 3261 compact header forms](https://www.rfc-editor.org/rfc/rfc3261#section-7.3.3)
- [RFC 3329 SIP security agreement](https://www.rfc-editor.org/rfc/rfc3329)
- [3GPP TS 33.203, ETSI edition](https://www.etsi.org/deliver/etsi_ts/133200_133299/133203/15.03.00_60/ts_133203v150300p.pdf)
- [SoftVoWifi SWu source](https://github.com/lilyzhaun/softvowifi-swu-go): reference for transport/address-family behavior; not a claim that its private IMS engine source was available.
