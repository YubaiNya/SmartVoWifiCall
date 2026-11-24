//go:build linux

package simauth

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"syscall"
	"time"
	"unsafe"
)

// Pure-Go Qualcomm QRTR (AF_QIPCRTR=42) + QMI UIM (service 0x0B) client.
// Allows hardware UICC AKA authentication even when the slot's cellular radio is powered off
// via enableModemForSlot on Qualcomm DSDS modems, without any external closed-source libraries.

const (
	afQipcrtr        = 42
	qrtrPortCtrl     = 0xFFFFFFFE
	qrtrNodeBcast    = 0xFFFFFFFF
	qrtrCmdNewServer = 4

	qmiServiceUIM       = 0x0B
	qmiUIMAuthenticate  = 0x0034
	qmiUIMGetCardStatus = 0x002F
)

type sockaddrQRTR struct {
	Family uint16
	Node   uint32
	Port   uint32
}

type QRTRProvider struct {
	Timeout time.Duration
}

func (q *QRTRProvider) Name() string {
	return "qrtr"
}

func (q *QRTRProvider) Authenticate(ctx context.Context, req AuthRequest) (*AuthResponse, error) {
	fd, err := syscall.Socket(afQipcrtr, syscall.SOCK_DGRAM, 0)
	if err != nil {
		return nil, fmt.Errorf("QRTR_UNAVAILABLE: %w", err)
	}
	defer syscall.Close(fd)

	timeout := q.Timeout
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	tv := syscall.NsecToTimeval(timeout.Nanoseconds())
	_ = syscall.SetsockoptTimeval(fd, syscall.SOL_SOCKET, syscall.SO_RCVTIMEO, &tv)
	_ = syscall.SetsockoptTimeval(fd, syscall.SOL_SOCKET, syscall.SO_SNDTIMEO, &tv)

	node, port, err := discoverQMIService(fd, qmiServiceUIM)
	if err != nil {
		return nil, err
	}

	// Session type per QMI UIM spec:
	// 0x00 = Primary GW (slot 0 USIM), 0x02 = Secondary GW (slot 1 USIM),
	// 0x04 = Primary 1X/ISIM, 0x06 = Non-provisioning; prefer GW slot session (0x00/0x02).
	sessionType := byte(0x00)
	if req.Slot == 1 {
		sessionType = 0x02
	}
	challenge, err := BuildUMTSChallenge(req.RAND, req.AUTN)
	if err != nil {
		return nil, err
	}

	// Build QMI UIM AUTHENTICATE (0x0034) request:
	// TLV 0x01: Session Information (session_type[1], aid_len[1]=0)
	// TLV 0x02: Authentication Data (context[1]=0x03 UMTS AKA, data_len[2 LE], challenge)
	tlv01 := []byte{0x01, 0x02, 0x00, sessionType, 0x00}
	authVal := make([]byte, 3+len(challenge))
	authVal[0] = 0x03 // UIM_AUTH_CONTEXT_3G_AKA
	binary.LittleEndian.PutUint16(authVal[1:3], uint16(len(challenge)))
	copy(authVal[3:], challenge)
	tlv02 := make([]byte, 3+len(authVal))
	tlv02[0] = 0x02
	binary.LittleEndian.PutUint16(tlv02[1:3], uint16(len(authVal)))
	copy(tlv02[3:], authVal)

	tlvBody := append(tlv01, tlv02...)
	txID := uint16(1)
	pkt := make([]byte, 7+len(tlvBody))
	pkt[0] = 0x00 // QMI Request
	binary.LittleEndian.PutUint16(pkt[1:3], txID)
	binary.LittleEndian.PutUint16(pkt[3:5], qmiUIMAuthenticate)
	binary.LittleEndian.PutUint16(pkt[5:7], uint16(len(tlvBody)))
	copy(pkt[7:], tlvBody)

	if err := qrtrSendTo(fd, node, port, pkt); err != nil {
		return nil, fmt.Errorf("QRTR_SEND_FAILED: %w", err)
	}

	buf := make([]byte, 4096)
	for {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		n, _, _, err := qrtrRecvFrom(fd, buf)
		if err != nil {
			return nil, fmt.Errorf("QRTR_RECV_FAILED: %w", err)
		}
		if n < 7 {
			continue
		}
		respFlag := buf[0]
		respTx := binary.LittleEndian.Uint16(buf[1:3])
		respMsg := binary.LittleEndian.Uint16(buf[3:5])
		if respFlag != 0x02 || respTx != txID || respMsg != qmiUIMAuthenticate {
			continue
		}
		tlvs := buf[7:n]
		authRaw, err := extractQMIUIMAuthTLV(tlvs)
		if err != nil {
			return nil, err
		}
		return ParseUMTSResponse(authRaw)
	}
}

func extractQMIUIMAuthTLV(tlvs []byte) ([]byte, error) {
	off := 0
	var resultCode uint16
	var errCode uint16
	var authData []byte
	for off+3 <= len(tlvs) {
		t := tlvs[off]
		l := int(binary.LittleEndian.Uint16(tlvs[off+1 : off+3]))
		off += 3
		if off+l > len(tlvs) {
			return nil, errors.New("QMI_TLV_TRUNCATED")
		}
		v := tlvs[off : off+l]
		off += l
		switch t {
		case 0x02: // QMI Result TLV
			if len(v) >= 4 {
				resultCode = binary.LittleEndian.Uint16(v[0:2])
				errCode = binary.LittleEndian.Uint16(v[2:4])
			}
		case 0x11: // Authenticate Result (2-byte LE len + raw TS 31.102 bytes)
			if len(v) >= 2 {
				dlen := int(binary.LittleEndian.Uint16(v[0:2]))
				if 2+dlen <= len(v) {
					authData = make([]byte, dlen)
					copy(authData, v[2:2+dlen])
				}
			}
		}
	}
	if resultCode != 0 {
		return nil, fmt.Errorf("QMI_UIM_ERROR_0x%04X", errCode)
	}
	if len(authData) == 0 {
		return nil, errors.New("QMI_UIM_EMPTY_AUTH_RESULT")
	}
	return authData, nil
}

func discoverQMIService(fd int, serviceID uint32) (uint32, uint32, error) {
	// QRTR_CMD_NEW_LOOKUP (cmd=9): [cmd:4][service:4][instance:4][node:4][port:4]
	var lookup [20]byte
	binary.LittleEndian.PutUint32(lookup[0:4], 9)
	binary.LittleEndian.PutUint32(lookup[4:8], serviceID)
	if err := qrtrSendTo(fd, qrtrNodeBcast, qrtrPortCtrl, lookup[:]); err != nil {
		return 0, 0, fmt.Errorf("QRTR_LOOKUP_SEND_FAILED: %w", err)
	}
	buf := make([]byte, 512)
	for i := 0; i < 16; i++ {
		n, _, _, err := qrtrRecvFrom(fd, buf)
		if err != nil {
			return 0, 0, fmt.Errorf("QRTR_LOOKUP_FAILED: %w", err)
		}
		if n < 20 {
			continue
		}
		cmd := binary.LittleEndian.Uint32(buf[0:4])
		svc := binary.LittleEndian.Uint32(buf[4:8])
		node := binary.LittleEndian.Uint32(buf[12:16])
		port := binary.LittleEndian.Uint32(buf[16:20])
		if cmd == qrtrCmdNewServer && svc == serviceID && port != 0 {
			return node, port, nil
		}
	}
	return 0, 0, errors.New("QRTR_UIM_SERVICE_NOT_FOUND")
}

func qrtrSendTo(fd int, node, port uint32, data []byte) error {
	sa := sockaddrQRTR{Family: afQipcrtr, Node: node, Port: port}
	var ptr unsafe.Pointer
	if len(data) > 0 {
		ptr = unsafe.Pointer(&data[0])
	}
	_, _, errno := syscall.Syscall6(
		syscall.SYS_SENDTO,
		uintptr(fd),
		uintptr(ptr),
		uintptr(len(data)),
		0,
		uintptr(unsafe.Pointer(&sa)),
		unsafe.Sizeof(sa),
	)
	if errno != 0 {
		return errno
	}
	return nil
}

func qrtrRecvFrom(fd int, buf []byte) (int, uint32, uint32, error) {
	var sa sockaddrQRTR
	saLen := uint32(unsafe.Sizeof(sa))
	n, _, errno := syscall.Syscall6(
		syscall.SYS_RECVFROM,
		uintptr(fd),
		uintptr(unsafe.Pointer(&buf[0])),
		uintptr(len(buf)),
		0,
		uintptr(unsafe.Pointer(&sa)),
		uintptr(unsafe.Pointer(&saLen)),
	)
	if errno != 0 {
		return 0, 0, 0, errno
	}
	return int(n), sa.Node, sa.Port, nil
}
