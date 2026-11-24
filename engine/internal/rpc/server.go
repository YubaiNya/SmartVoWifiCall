package rpc

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"time"
)

const MaxLineBytes = 1024 * 1024

// RPCError carries an integer error code compatible with EngineRpcClient.RpcException.
type RPCError struct {
	Code    int64
	Message string
}

func (e *RPCError) Error() string {
	return fmt.Sprintf("RPC %d: %s", e.Code, e.Message)
}

// Handler defines the backend operations exposed over the control Unix socket.
type Handler interface {
	GetConfig() map[string]any
	GetStatus() map[string]any
	GetCallSnapshot() (map[string]any, error)
	Dial(ctx context.Context, recipient string) (map[string]any, error)
	Answer(ctx context.Context, callID string) (map[string]any, error)
	Reject(ctx context.Context, callID string) (map[string]any, error)
	Hangup(ctx context.Context, callID string) (map[string]any, error)
	CancelCall(ctx context.Context, callID string) (map[string]any, error)
	SendDTMF(ctx context.Context, callID, digit string, durationMs int) (map[string]any, error)
	AudioStart(ctx context.Context, callID string, sampleRate, channels int) (map[string]any, error)
	AudioStop(ctx context.Context, callID string) (map[string]any, error)
	SendSMS(ctx context.Context, number, text string) (map[string]any, error)
	Stop()
}

type requestFrame struct {
	ID     int64          `json:"id"`
	Method string         `json:"method"`
	Params map[string]any `json:"params"`
}

type connClient struct {
	conn net.Conn
	wmu  sync.Mutex
}

func (c *connClient) sendJSON(v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	data = append(data, '\n')
	c.wmu.Lock()
	defer c.wmu.Unlock()
	_, err = c.conn.Write(data)
	return err
}

// Server serves newline-delimited UTF-8 JSON RPC v1 on <iccid>.sock with full multi-connection concurrency.
type Server struct {
	path     string
	listener net.Listener
	handler  Handler
	ctx      context.Context
	cancel   context.CancelFunc

	mu      sync.Mutex
	clients map[*connClient]struct{}
	closed  bool
	wg      sync.WaitGroup
}

func NewServer(socketPath string, handler Handler) (*Server, error) {
	if handler == nil {
		return nil, errors.New("RPC handler required")
	}
	_ = os.Remove(socketPath)
	ln, err := net.Listen("unix", socketPath)
	if err != nil {
		return nil, err
	}
	_ = os.Chmod(socketPath, 0o660)
	ctx, cancel := context.WithCancel(context.Background())
	s := &Server{
		path:     socketPath,
		listener: ln,
		handler:  handler,
		ctx:      ctx,
		cancel:   cancel,
		clients:  make(map[*connClient]struct{}),
	}
	s.wg.Add(1)
	go s.acceptLoop()
	return s, nil
}

// BroadcastEvent pushes an asynchronous {event, data} frame to all connected control clients.
func (s *Server) BroadcastEvent(name string, data map[string]any) {
	if name == "" {
		return
	}
	if data == nil {
		data = map[string]any{}
	}
	frame := map[string]any{
		"event": name,
		"data":  data,
	}
	s.mu.Lock()
	targets := make([]*connClient, 0, len(s.clients))
	for c := range s.clients {
		targets = append(targets, c)
	}
	s.mu.Unlock()
	for _, c := range targets {
		_ = c.sendJSON(frame)
	}
}

func (s *Server) acceptLoop() {
	defer s.wg.Done()
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		c := &connClient{conn: conn}
		s.mu.Lock()
		if s.closed {
			s.mu.Unlock()
			_ = conn.Close()
			return
		}
		s.clients[c] = struct{}{}
		s.mu.Unlock()

		s.wg.Add(1)
		go s.handleConn(c)
	}
}

func (s *Server) handleConn(c *connClient) {
	defer s.wg.Done()
	defer func() {
		s.mu.Lock()
		delete(s.clients, c)
		s.mu.Unlock()
		_ = c.conn.Close()
	}()

	reader := bufio.NewReaderSize(c.conn, 64*1024)
	for {
		line, err := readLineLimited(reader, MaxLineBytes)
		if err != nil {
			return
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		var req requestFrame
		if err := json.Unmarshal([]byte(line), &req); err != nil {
			_ = c.sendJSON(map[string]any{
				"id":    0,
				"error": map[string]any{"code": 400, "message": "INVALID_JSON"},
			})
			continue
		}
		if req.ID <= 0 || req.Method == "" {
			_ = c.sendJSON(map[string]any{
				"id":    req.ID,
				"error": map[string]any{"code": 400, "message": "INVALID_REQUEST"},
			})
			continue
		}
		// Run each request in its own goroutine so slow operations (e.g. dial) never block
		// ping, getStatus, getCallSnapshot, or cancelCall even on the same connection.
		s.wg.Add(1)
		go func(r requestFrame) {
			defer s.wg.Done()
			res, rerr := s.dispatch(r.Method, r.Params)
			if rerr != nil {
				var rpcErr *RPCError
				code := int64(500)
				msg := rerr.Error()
				if errors.As(rerr, &rpcErr) {
					code = rpcErr.Code
					msg = rpcErr.Message
				}
				_ = c.sendJSON(map[string]any{
					"id":    r.ID,
					"error": map[string]any{"code": code, "message": msg},
				})
				return
			}
			if res == nil {
				res = map[string]any{}
			}
			_ = c.sendJSON(map[string]any{
				"id":     r.ID,
				"result": res,
			})
		}(req)
	}
}

func (s *Server) dispatch(method string, params map[string]any) (map[string]any, error) {
	if params == nil {
		params = map[string]any{}
	}
	switch method {
	case "hello":
		v, ok := asInt64(params["version"])
		if !ok || v != 1 {
			return nil, &RPCError{Code: 1001, Message: "UNSUPPORTED_PROTOCOL_VERSION"}
		}
		return map[string]any{
			"version":       1,
			"engine":        "smartvowificall-engine",
			"engineVersion": "0.1.0-native",
		}, nil
	case "ping":
		return map[string]any{
			"ok": true,
			"ts": time.Now().UnixMilli(),
		}, nil
	case "getClientCapabilities":
		return map[string]any{
			"schema": 1,
			"capabilities": []string{
				"config.metadata.v1",
				"rpc.getConfig",
				"rpc.getStatus",
				"rpc.ping",
				"rpc.stop",
				"rpc.call.v1",
				"rpc.audio.v1",
				"rpc.sms.v1",
			},
		}, nil
	case "getConfig":
		return s.handler.GetConfig(), nil
	case "getStatus":
		return s.handler.GetStatus(), nil
	case "getCallSnapshot":
		return s.handler.GetCallSnapshot()
	case "dial":
		recipient, _ := params["recipient"].(string)
		if recipient == "" {
			return nil, &RPCError{Code: 2001, Message: "RECIPIENT_REQUIRED"}
		}
		return s.handler.Dial(s.ctx, recipient)
	case "answer":
		callID, _ := params["callId"].(string)
		if callID == "" {
			return nil, &RPCError{Code: 2001, Message: "CALL_ID_REQUIRED"}
		}
		return s.handler.Answer(s.ctx, callID)
	case "reject":
		callID, _ := params["callId"].(string)
		if callID == "" {
			return nil, &RPCError{Code: 2001, Message: "CALL_ID_REQUIRED"}
		}
		return s.handler.Reject(s.ctx, callID)
	case "hangup":
		callID, _ := params["callId"].(string)
		if callID == "" {
			return nil, &RPCError{Code: 2001, Message: "CALL_ID_REQUIRED"}
		}
		return s.handler.Hangup(s.ctx, callID)
	case "cancelCall":
		callID, _ := params["callId"].(string)
		if callID == "" {
			return nil, &RPCError{Code: 2001, Message: "CALL_ID_REQUIRED"}
		}
		return s.handler.CancelCall(s.ctx, callID)
	case "sendDtmf":
		callID, _ := params["callId"].(string)
		digit, _ := params["digit"].(string)
		dur, _ := asInt64(params["durationMs"])
		if dur <= 0 {
			dur = 160
		}
		if callID == "" || len(digit) != 1 || !strings.Contains("0123456789*#ABCD", digit) {
			return nil, &RPCError{Code: 2001, Message: "INVALID_DTMF"}
		}
		return s.handler.SendDTMF(s.ctx, callID, digit, int(dur))
	case "audioStart":
		callID, _ := params["callId"].(string)
		sr, _ := asInt64(params["sampleRate"])
		ch, _ := asInt64(params["channels"])
		if sr <= 0 {
			sr = PCMSampleRateHz
		}
		if ch <= 0 {
			ch = PCMChannelCount
		}
		if callID == "" {
			return nil, &RPCError{Code: 2001, Message: "CALL_ID_REQUIRED"}
		}
		return s.handler.AudioStart(s.ctx, callID, int(sr), int(ch))
	case "audioStop":
		callID, _ := params["callId"].(string)
		if callID == "" {
			return nil, &RPCError{Code: 2001, Message: "CALL_ID_REQUIRED"}
		}
		return s.handler.AudioStop(s.ctx, callID)
	case "sendSms":
		number, _ := params["number"].(string)
		text, _ := params["text"].(string)
		if number == "" {
			return nil, &RPCError{Code: 2001, Message: "SMS_NUMBER_REQUIRED"}
		}
		return s.handler.SendSMS(s.ctx, number, text)
	case "stop":
		go s.handler.Stop()
		return map[string]any{"ok": true, "stopping": true}, nil
	default:
		return nil, &RPCError{Code: 404, Message: "UNKNOWN_METHOD"}
	}
}

func (s *Server) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	for c := range s.clients {
		_ = c.conn.Close()
	}
	s.mu.Unlock()
	s.cancel()
	err := s.listener.Close()
	s.wg.Wait()
	_ = os.Remove(s.path)
	return err
}

func readLineLimited(r *bufio.Reader, limit int) (string, error) {
	var buf []byte
	for {
		chunk, isPrefix, err := r.ReadLine()
		if err != nil {
			return "", err
		}
		if len(buf)+len(chunk) > limit {
			return "", errors.New("RPC_FRAME_TOO_LARGE")
		}
		buf = append(buf, chunk...)
		if !isPrefix {
			return string(buf), nil
		}
	}
}

func asInt64(v any) (int64, bool) {
	switch n := v.(type) {
	case float64:
		if n != float64(int64(n)) {
			return 0, false
		}
		return int64(n), true
	case int:
		return int64(n), true
	case int64:
		return n, true
	case json.Number:
		i, err := n.Int64()
		return i, err == nil
	default:
		return 0, false
	}
}
