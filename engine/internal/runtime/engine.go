package runtime

import (
	"context"
	"errors"
	"fmt"
	"math/rand/v2"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/behavior"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/config"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/diag"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ikev2"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/media"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/rpc"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/simauth"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/sip"
)

type Options struct {
	Config    *config.Config
	SimMode   string // auto | binder | qrtr | mock
	SimSocket string
	SocketDir string
	LogDir    string
	SimAuth   simauth.Provider // optional override for tests
	EPDGPort  int              // default 500
	NattPort  int              // default 4500
	PCSCFPort uint16           // default 5060
}

// Engine orchestrates the full self-owned VoWiFi stack:
// Config -> SimAuth (Binder/QRTR) -> IKEv2 + IPsec ESP -> IMS SIP/SMS -> RTP Media <-> Unix RPC/Audio sockets.
type Engine struct {
	opts      Options
	logger    *diag.Logger
	simAuth   simauth.Provider
	startedAt time.Time
	ctx       context.Context
	cancel    context.CancelFunc
	behavior  *behavior.Engine

	rpcSrv   *rpc.Server
	audioSrv *rpc.AudioServer

	mu                   sync.Mutex
	imsState             string
	tunState             string
	epdgIP               string
	localIP              string
	pcscfIP              string
	preferredAuthProfile int
	ikeClient            *ikev2.Client
	sipClient            *sip.Client
	mediaSess            *media.Session
	mediaCall            string
	audioOn              bool
	epoch                string
	stopOnce             sync.Once
	stopCh               chan struct{}
	reconnectCh          chan struct{}
	callEndedCh          chan struct{}
	lastReconnect        time.Time
	wg                   sync.WaitGroup
}

func Start(opts Options) (*Engine, error) {
	if opts.Config == nil {
		return nil, errors.New("ENGINE_CONFIG_REQUIRED")
	}
	if opts.SocketDir == "" {
		opts.SocketDir = "/data/local/tmp/vowifi"
	}
	if opts.EPDGPort <= 0 {
		opts.EPDGPort = 500
	}
	if opts.NattPort <= 0 {
		opts.NattPort = 4500
	}
	if opts.PCSCFPort == 0 {
		opts.PCSCFPort = 5060
	}
	logger, err := diag.NewLogger(opts.LogDir)
	if err != nil {
		logger, _ = diag.NewLogger("")
	}

	simProvider := opts.SimAuth
	if simProvider == nil {
		simProvider = buildSimProvider(opts.SimMode, opts.SimSocket)
	}

	ctx, cancel := context.WithCancel(context.Background())
	e := &Engine{
		opts:        opts,
		logger:      logger,
		simAuth:     simProvider,
		startedAt:   time.Now(),
		imsState:    "CONNECTING",
		tunState:    "CONNECTING",
		epoch:       fmt.Sprintf("ep-%d", time.Now().UnixNano()),
		stopCh:      make(chan struct{}),
		reconnectCh: make(chan struct{}, 1),
		callEndedCh: make(chan struct{}, 1),
		ctx:         ctx, cancel: cancel, behavior: behavior.New(),
	}

	ctrlPath := filepath.Join(opts.SocketDir, opts.Config.ICCID+".sock")
	audioPath := filepath.Join(opts.SocketDir, opts.Config.ICCID+".audio.sock")

	audioSrv, err := rpc.NewAudioServer(audioPath, e.onUplinkPCM)
	if err != nil {
		cancel()
		_ = logger.Close()
		return nil, fmt.Errorf("AUDIO_SOCKET_FAILED: %w", err)
	}
	e.audioSrv = audioSrv

	rpcSrv, err := rpc.NewServer(ctrlPath, e)
	if err != nil {
		cancel()
		_ = audioSrv.Close()
		_ = logger.Close()
		return nil, fmt.Errorf("CONTROL_SOCKET_FAILED: %w", err)
	}
	e.rpcSrv = rpcSrv

	e.logger.Info("engine", "native SmartVoWifiCall engine started", map[string]any{
		"simMode":   simProvider.Name(),
		"build":     diag.BuildInfo(),
		"epdg":      opts.Config.EffectiveEPDG(),
		"processId": os.Getpid(), "processStartedAtMs": e.startedAt.UnixMilli(),
	})

	e.wg.Add(1)
	go e.supervisorLoop()
	return e, nil
}

func buildSimProvider(mode, simSocket string) simauth.Provider {
	qrtr := &simauth.QRTRProvider{}
	binder := &simauth.BinderProvider{SocketPath: simSocket}
	switch mode {
	case "binder":
		return binder
	case "qrtr":
		// Prefer QRTR on Qualcomm, automatically fall back to universal Binder if QRTR unavailable.
		if simSocket != "" {
			return &simauth.AutoProvider{Providers: []simauth.Provider{qrtr, binder}}
		}
		return qrtr
	case "mock":
		return &simauth.MockProvider{KeyMaterial: []byte("offline-mock")}
	default:
		// Chipset-aware AutoProvider:
		// Qualcomm (QRTR present): QRTR first (works even when enableModemForSlot=false disables RIL), Binder fallback.
		// MediaTek / Google Tensor / Samsung Exynos: Universal Root Binder first, QRTR fallback.
		return simauth.NewChipsetAutoProvider(simauth.DetectQRTRPresent(), binder, qrtr)
	}
}

func (e *Engine) Wait() {
	<-e.stopCh
	e.wg.Wait()
}

func (e *Engine) supervisorLoop() {
	defer e.wg.Done()
	defer e.logger.Close()
	defer e.teardownSession()
	for {
		select {
		case <-e.stopCh:
			return
		default:
		}
		e.behavior.BeginAttempt()
		err := e.connectOnce()
		if err == nil {
			err = e.maintainRegistration()
		}
		if e.ctx.Err() != nil {
			return
		}
		if err == nil {
			continue
		} // accepted explicit reconnect, verified idle
		stage := e.behavior.Snapshot(time.Now()).State
		e.teardownSession()
		failure := diagnose(stage, err)
		delay := e.behavior.Fail(failure, time.Now(), time.Duration(rand.IntN(1001))*time.Millisecond)
		e.logger.Warn("engine", failure.Cause, nil)
		e.setStates("BACKOFF", "DISCONNECTED")
		e.broadcastBehavior()
		// Discard requests queued during the failed attempt; they must not bypass
		// its newly learned cooldown or carrier Retry-After.
		select {
		case <-e.reconnectCh:
		default:
		}
		var timer *time.Timer
		var retry <-chan time.Time
		if !failure.ManualOnly && !failure.RetryDisabled {
			timer = time.NewTimer(delay)
			retry = timer.C
		}
		select {
		case <-e.stopCh:
			if timer != nil {
				timer.Stop()
			}
			return
		case <-e.reconnectCh:
		case <-retry:
		}
		if timer != nil {
			timer.Stop()
		}
	}
}

func (e *Engine) connectOnce() error {
	ctx, cancel := context.WithTimeout(e.ctx, 35*time.Second)
	defer cancel()

	e.setStates("CONNECTING", "CONNECTING")
	epdgAddr := e.opts.Config.EPDGAddr
	nattAddr := ""
	if epdgAddr == "" {
		e.observe(behavior.Discovery, "DNS_QUERY_STARTED")
		ip, err := config.ResolveHostIPv4(ctx, e.opts.Config.EffectiveEPDG(), e.opts.Config.DNS)
		if err != nil {
			return fmt.Errorf("EPDG_DNS_FAILED: %w", err)
		}
		epdgAddr = net.JoinHostPort(ip.String(), fmt.Sprintf("%d", e.opts.EPDGPort))
		nattAddr = net.JoinHostPort(ip.String(), fmt.Sprintf("%d", e.opts.NattPort))
		e.mu.Lock()
		e.epdgIP = ip.String()
		e.mu.Unlock()
	} else {
		nattAddr = epdgAddr
	}

	candidates := e.opts.Config.CandidateEAPIdentities()
	e.mu.Lock()
	prefIdx := e.preferredAuthProfile
	e.mu.Unlock()
	if prefIdx < 0 || prefIdx >= len(candidates) {
		prefIdx = 0
	}
	order := make([]int, 0, len(candidates))
	order = append(order, prefIdx)
	for i := range candidates {
		if i != prefIdx {
			order = append(order, i)
		}
	}

	var ikeCli *ikev2.Client
	e.observe(behavior.IKEConnecting, "IKE_CONNECT_STARTED")
	var lastErr error
	hint, _ := config.LookupCarrierHint(e.opts.Config.MCC, e.opts.Config.MNC)
	for step, idx := range order {
		cand := candidates[idx]
		cli, err := ikev2.Connect(ctx, ikev2.SessionConfig{
			EPDGAddr:       epdgAddr,
			NATTAddr:       nattAddr,
			EAPIdentity:    cand.Identity,
			APN:            cand.APN,
			IMEI:           e.opts.Config.IMEI,
			ProfileTag:     cand.Tag,
			Slot:           e.opts.Config.Slot,
			ICCID:          e.opts.Config.ICCID,
			SimAuth:        e.simAuth,
			Logger:         e.logger,
			PreferIPv6:     hint.PreferIPv6,
			ProxyTransport: e.opts.Config.Transport == "iface:wfctun0" || e.opts.Config.Transport == "iface:wfctun1",
		})
		if err == nil {
			ikeCli = cli
			e.mu.Lock()
			e.preferredAuthProfile = idx
			e.mu.Unlock()
			e.logger.Info("engine", "IKEv2 session established", map[string]any{
				"profile": cand.Tag,
			})
			break
		}
		lastErr = err
		if ikev2.IsAuthFailed(err) && step+1 < len(order) {
			e.logger.Warn("engine", fmt.Sprintf("IKE_AUTH rejected on profile=%s (%v); trying next 3GPP NAI profile", cand.Tag, err), nil)
			continue
		}
		return err
	}
	if ikeCli == nil {
		return lastErr
	}

	cfgRes := ikeCli.Config()
	if cfgRes == nil || len(cfgRes.PCSCF) == 0 {
		_ = ikeCli.Close()
		return errors.New("EPDG_NO_PCSCF")
	}

	e.mu.Lock()
	if err := ctx.Err(); err != nil {
		e.mu.Unlock()
		_ = ikeCli.Close()
		return err
	}
	e.ikeClient = ikeCli
	e.localIP = cfgRes.LocalIP.String()
	e.pcscfIP = cfgRes.PCSCF[0].String()
	e.tunState = "CONNECTED"
	e.imsState = "REGISTERING"
	e.mu.Unlock()
	e.observe(behavior.IPsecEstablished, "AUTHENTICATED_CHILD_SA_CREATED")
	e.observe(behavior.IMSRegistering, "SIP_REGISTER_STARTED")

	sipCli, err := sip.NewClient(sip.ClientConfig{
		Tunnel:       ikeCli.Tunnel(),
		PCSCF:        cfgRes.PCSCF[0],
		PCSCFPort:    e.opts.PCSCFPort,
		IMSRealm:     e.opts.Config.IMSRealm,
		IMPI:         e.opts.Config.EffectiveIMPI(),
		IMPU:         e.opts.Config.EffectiveIMPU(),
		Slot:         e.opts.Config.Slot,
		ICCID:        e.opts.Config.ICCID,
		SMSC:         e.opts.Config.SMSC,
		SimAuth:      e.simAuth,
		IncludeAMR:   media.HasAMRSupport(),
		RequireIPSec: hint.RequireIMSIPSec,
		Logger:       e.logger,
		OnEvent:      e.onSIPEvent,
		OnMediaStart: e.onMediaStart,
		OnMediaStop:  e.onMediaStop,
	})
	if err != nil {
		_ = ikeCli.Close()
		return err
	}
	if err := sipCli.Register(ctx); err != nil {
		_ = sipCli.Close()
		_ = ikeCli.Close()
		return err
	}

	e.mu.Lock()
	if err := ctx.Err(); err != nil {
		e.mu.Unlock()
		_ = sipCli.Close()
		_ = ikeCli.Close()
		return err
	}
	e.sipClient = sipCli
	e.imsState = "REGISTERED"
	tunNow := e.tunState
	e.mu.Unlock()
	e.observe(behavior.IMSRegistered, "SIP_REGISTER_200_AND_CHILD_SA")
	e.broadcastState("REGISTERED", tunNow)
	return nil
}

func (e *Engine) maintainRegistration() error {
	e.mu.Lock()
	ic := e.ikeClient
	s := e.sipClient
	e.mu.Unlock()
	if s == nil {
		return errors.New("SIP_CLIENT_UNAVAILABLE")
	}
	timer := time.NewTimer(s.RegistrationRefreshDelay())
	defer timer.Stop()
	var deadCh <-chan struct{}
	if ic != nil {
		deadCh = ic.Dead()
	}
	for {
		select {
		case <-e.stopCh:
			e.teardownSession()
			return e.ctx.Err()
		case <-deadCh:
			e.logger.Warn("engine", "IKEv2 tunnel lost; tearing down IMS session", nil)
			cause := ic.DeadReason()
			e.teardownSession()
			return errors.New(cause)
		case <-e.reconnectCh:
			if !s.TryBeginRecovery() {
				e.logger.Info("engine", "reconnect refused: call started before execution", nil)
				continue
			}
			e.logger.Info("engine", "fast reconnect requested; tearing down stale tunnel", nil)
			e.teardownSession()
			return nil
		case <-timer.C:
			e.observe(behavior.IMSRegistering, "SIP_REFRESH_STARTED")
			ctx, cancel := context.WithTimeout(e.ctx, 15*time.Second)
			err := s.Register(ctx)
			cancel()
			if err != nil {
				e.setStates("DISCONNECTED", "CONNECTED")
				// A failed registration refresh does not by itself kill an active
				// SIP dialog. Wait for call end; a dead tunnel or Stop still exits.
				for !s.TryBeginRecovery() {
					select {
					case <-e.callEndedCh:
					case <-deadCh:
						cause := ic.DeadReason()
						e.teardownSession()
						return errors.New(cause)
					case <-e.stopCh:
						return e.ctx.Err()
					}
				}
				e.teardownSession()
				return err
			}
			e.observe(behavior.IMSRegistered, "SIP_REFRESH_200")
			timer.Reset(s.RegistrationRefreshDelay())
		}
	}
}

func (e *Engine) observe(state behavior.State, evidence string) {
	e.behavior.Observe(state, evidence)
	e.broadcastBehavior()
}

func (e *Engine) broadcastBehavior() {
	if e.rpcSrv != nil {
		e.rpcSrv.BroadcastEvent("behaviorState", map[string]any{"behavior": e.behavior.Snapshot(time.Now())})
	}
}

func (e *Engine) teardownSession() {
	e.mu.Lock()
	ms := e.mediaSess
	e.mediaSess = nil
	sc := e.sipClient
	e.sipClient = nil
	ic := e.ikeClient
	e.ikeClient = nil
	changed := e.imsState != "DISCONNECTED" || e.tunState != "DISCONNECTED"
	e.imsState = "DISCONNECTED"
	e.tunState = "DISCONNECTED"
	e.mu.Unlock()
	if changed {
		e.broadcastState("DISCONNECTED", "DISCONNECTED")
	}
	if ms != nil {
		_ = ms.Close()
	}
	if sc != nil {
		_ = sc.Close()
	}
	if ic != nil {
		_ = ic.Close()
	}
}

func (e *Engine) setStates(ims, tun string) {
	e.mu.Lock()
	changed := e.imsState != ims || e.tunState != tun
	e.imsState = ims
	e.tunState = tun
	e.mu.Unlock()
	if changed {
		e.broadcastState(ims, tun)
	}
}

func (e *Engine) broadcastState(ims, tun string) {
	if e.rpcSrv != nil {
		e.rpcSrv.BroadcastEvent("imsState", map[string]any{
			"ims":    ims,
			"tunnel": tun,
		})
	}
}

func (e *Engine) onSIPEvent(name string, data map[string]any) {
	if name == "callEnded" {
		select {
		case e.callEndedCh <- struct{}{}:
		default:
		}
	}
	if e.rpcSrv != nil {
		e.rpcSrv.BroadcastEvent(name, data)
	}
}

func (e *Engine) onMediaStart(callID string, localRTPPort uint16, remote *sip.MediaAnswer) {
	e.mu.Lock()
	ic := e.ikeClient
	old := e.mediaSess
	e.mediaSess = nil
	e.mediaCall = callID
	e.mu.Unlock()
	if old != nil {
		_ = old.Close()
	}
	if ic == nil || ic.Tunnel() == nil || remote == nil {
		return
	}
	codec := media.NewCodec(remote.Codec, remote.PayloadTyp)
	sess, err := media.StartSession(ic.Tunnel(), localRTPPort, remote.RemoteIP, remote.RemotePort,
		codec, remote.DTMFType, func(pcm []byte) {
			if e.audioSrv != nil {
				e.audioSrv.SendDownlink(pcm)
			}
		})
	if err != nil {
		e.logger.Warn("media", "failed to start RTP session: "+err.Error(), nil)
		return
	}
	e.mu.Lock()
	e.mediaSess = sess
	e.mu.Unlock()
}

func (e *Engine) onMediaStop(callID string) {
	e.mu.Lock()
	ms := e.mediaSess
	e.mediaSess = nil
	e.mediaCall = ""
	e.audioOn = false
	e.mu.Unlock()
	if ms != nil {
		_ = ms.Close()
	}
}

func (e *Engine) onUplinkPCM(pcm []byte) {
	e.mu.Lock()
	ms := e.mediaSess
	on := e.audioOn
	e.mu.Unlock()
	if ms != nil && on {
		_ = ms.SendUplinkPCM(pcm)
	}
}

// --- rpc.Handler implementation ---

func (e *Engine) GetConfig() map[string]any {
	return e.opts.Config.SanitizedMap()
}

func (e *Engine) GetStatus() map[string]any {
	e.mu.Lock()
	defer e.mu.Unlock()
	ims := e.imsState
	state := e.behavior.Snapshot(time.Now())
	expiryKnown := false
	if sc := e.sipClient; sc != nil {
		expiryKnown = sc.RegistrationExpiryKnown()
		if ims == "REGISTERED" && !sc.Registered() {
			ims = "DISCONNECTED"
			state.State, state.Evidence = behavior.IMSFailed, "REGISTRATION_NOT_CURRENT"
		}
		if call := sc.CallSnapshot(); call["stage"] == "IN_CALL" {
			state.State, state.Evidence = behavior.ActiveCall, "SIP_DIALOG_IN_CALL"
		}
	}
	return map[string]any{
		"ims":                     ims,
		"tunnel":                  e.tunState,
		"simMode":                 e.simAuth.Name(),
		"epdg":                    e.opts.Config.EffectiveEPDG(),
		"uptimeSec":               int64(time.Since(e.startedAt).Seconds()),
		"processId":               os.Getpid(),
		"processStartedAtMs":      e.startedAt.UnixMilli(),
		"behavior":                state,
		"registrationExpiryKnown": expiryKnown,
	}
}

func (e *Engine) GetCallSnapshot() (map[string]any, error) {
	e.mu.Lock()
	sc := e.sipClient
	ep := e.epoch
	e.mu.Unlock()
	if sc != nil {
		return sc.CallSnapshot(), nil
	}
	return map[string]any{
		"epoch":     ep,
		"revision":  0,
		"stage":     "IDLE",
		"callId":    "",
		"direction": "NONE",
		"peer":      "",
	}, nil
}

func (e *Engine) Dial(ctx context.Context, recipient string) (map[string]any, error) {
	sc := e.requireSIP()
	if sc == nil {
		return nil, &rpc.RPCError{Code: 2002, Message: "IMS_NOT_REGISTERED"}
	}
	return sc.Dial(ctx, recipient)
}

func (e *Engine) Answer(ctx context.Context, callID string) (map[string]any, error) {
	sc := e.requireSIP()
	if sc == nil {
		return nil, &rpc.RPCError{Code: 2002, Message: "IMS_NOT_REGISTERED"}
	}
	return sc.Answer(ctx, callID)
}

func (e *Engine) Reject(ctx context.Context, callID string) (map[string]any, error) {
	sc := e.requireSIP()
	if sc == nil {
		return map[string]any{"callId": callID, "state": "ENDED"}, nil
	}
	return sc.Reject(ctx, callID)
}

func (e *Engine) Hangup(ctx context.Context, callID string) (map[string]any, error) {
	sc := e.requireSIP()
	if sc == nil {
		return map[string]any{"callId": callID, "state": "ENDED"}, nil
	}
	return sc.Hangup(ctx, callID)
}

func (e *Engine) CancelCall(ctx context.Context, callID string) (map[string]any, error) {
	sc := e.requireSIP()
	if sc == nil {
		return map[string]any{"callId": callID, "state": "ENDED"}, nil
	}
	return sc.CancelCall(ctx, callID)
}

func (e *Engine) SendDTMF(ctx context.Context, callID, digit string, durationMs int) (map[string]any, error) {
	e.mu.Lock()
	ms := e.mediaSess
	e.mu.Unlock()
	if ms == nil {
		return nil, &rpc.RPCError{Code: 2004, Message: "MEDIA_NOT_ACTIVE"}
	}
	if err := ms.SendDTMF(digit[0], durationMs); err != nil {
		return nil, &rpc.RPCError{Code: 2004, Message: err.Error()}
	}
	return map[string]any{"ok": true}, nil
}

func (e *Engine) AudioStart(ctx context.Context, callID string, sampleRate, channels int) (map[string]any, error) {
	e.mu.Lock()
	e.audioOn = true
	e.mu.Unlock()
	return map[string]any{"ok": true, "sampleRate": sampleRate, "channels": channels}, nil
}

func (e *Engine) AudioStop(ctx context.Context, callID string) (map[string]any, error) {
	e.mu.Lock()
	e.audioOn = false
	e.mu.Unlock()
	return map[string]any{"ok": true}, nil
}

func (e *Engine) SendSMS(ctx context.Context, number, text string) (map[string]any, error) {
	sc := e.requireSIP()
	if sc == nil {
		return nil, &rpc.RPCError{Code: 2002, Message: "IMS_NOT_REGISTERED"}
	}
	return sc.SendSMS(ctx, number, text)
}

func (e *Engine) Reconnect() map[string]any {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.ctx.Err() != nil {
		return map[string]any{"ok": false, "reason": "STOPPED"}
	}
	if e.sipClient == nil && (e.imsState == "CONNECTING" || e.imsState == "REGISTERING") {
		return map[string]any{"ok": false, "reason": "RECOVERY_IN_PROGRESS"}
	}
	if e.sipClient != nil && e.sipClient.CallSnapshot()["stage"] != "IDLE" {
		return map[string]any{"ok": false, "reason": "CALL_ACTIVE"}
	}
	now := time.Now()
	if !e.behavior.CanRequest(now) || (!e.lastReconnect.IsZero() && now.Sub(e.lastReconnect) < 30*time.Second) {
		return map[string]any{"ok": false, "reason": "RETRY_BACKOFF"}
	}
	select {
	case e.reconnectCh <- struct{}{}:
		e.lastReconnect = now
	default:
		return map[string]any{"ok": false, "reason": "RECOVERY_ALREADY_PENDING"}
	}
	return map[string]any{"ok": true, "reconnecting": true, "result": "PENDING_VERIFICATION"}
}

func (e *Engine) Stop() {
	e.StopWithReason("STOP_REQUEST")
}

// Reasons are fixed lifecycle categories, never caller-supplied protocol text.
func (e *Engine) StopWithReason(reason string) {
	switch reason {
	case "SIGTERM", "SIGINT", "RPC_STOP", "STOP_REQUEST":
	default:
		reason = "UNSPECIFIED"
	}
	e.stopOnce.Do(func() {
		slot := -1
		if e.opts.Config != nil {
			slot = e.opts.Config.Slot
		}
		e.logger.Info("lifecycle", "engine stop requested", map[string]any{
			"event": "STOP_REQUEST", "reason": reason, "slot": slot,
			"processId": os.Getpid(), "processStartedAtMs": e.startedAt.UnixMilli(),
			"uptimeSec": int64(time.Since(e.startedAt).Seconds()), "build": diag.BuildInfo(),
		})
		e.cancel()
		close(e.stopCh)
		e.teardownSession()
		if e.rpcSrv != nil {
			_ = e.rpcSrv.Close()
		}
		if e.audioSrv != nil {
			_ = e.audioSrv.Close()
		}
		if e.logger != nil {
			_ = e.logger.Close()
		}
	})
}

func (e *Engine) requireSIP() *sip.Client {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.sipClient
}
