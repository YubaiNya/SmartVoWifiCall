/**
 * Independently implemented adapter based on observed VoWiFi engine protocol v1, not copied upstream
 * code. Contract evidence: D:/vowifi/work/apk/smali_classes2/com/vowifi/sw/manager/data/
 * EngineProtocolCodec.smali, LocalSocketEngineClient.smali, CallProtocolCodec.smali,
 * SmsProtocolCodec.smali, PcmFrameCodec.smali and AndroidCallAudioSocket$Companion.smali.
 * Baseline APK SHA-256 a990246826f71dced3889d8e0cbebf690f25d87c70ffbf60c2c6160d5267c712.
 * REA request evidence: ev_7a6c7c9b533119bb649f4b76a86c6008d627ee425e47511c21274b70e7c364a9.
 * Audio evidence: ev_f97944bb588e676238d3b929f884eced523eb598a3400d2651cc9c005a287e16.
 *
 * The observed baseline was v0.3.45. The current device's engine was reported as v0.3.48;
 * runtime hello/capability checks are required and no private negative-duration DTMF extension is
 * used. D:/vowifi/INSTALL-INTERFACES.txt says the complete upstream engine source was not obtained.
 * D:/vowifi/work/module/licenses/APP-NOTICE.txt excludes the private Go engine from its source
 * grant. This package neither implements nor redistributes the private engine. It performs no SIM
 * AKA itself. No ImsService, routing selection, socket permission setup, or Android audio policy is
 * implied here.
 */
package com.smartvowificall.ims.engine;
