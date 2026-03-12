package udpgw

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"math/rand"
	"net"
	"sync"
	"time"
)

const (
	FlagKeepalive = 1 << 0
	FlagRebind    = 1 << 1
	FlagDNS       = 1 << 2
	FlagIPv6      = 1 << 3
)

// PacketConn wraps a TCP connection to a badvpn-udpgw server and implements net.PacketConn.
type PacketConn struct {
	conn      net.Conn
	mu        sync.Mutex
	conid     uint16
	firstSend bool
	ctx       context.Context
	cancel    context.CancelFunc
}

func NewPacketConn(conn net.Conn) *PacketConn {
	ctx, cancel := context.WithCancel(context.Background())
	pc := &PacketConn{
		conn:      conn,
		conid:     uint16(rand.Intn(65535-1) + 1),
		firstSend: true,
		ctx:       ctx,
		cancel:    cancel,
	}

	// Start keepalive routine (badvpn udpgw requires this to prevent timeout)
	go pc.keepaliveLoop()

	return pc
}

func (c *PacketConn) keepaliveLoop() {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-c.ctx.Done():
			return
		case <-ticker.C:
			// Send keepalive packet
			c.mu.Lock()
			// length(2) + flags(1) + conid(2) = 5 bytes total. payload is 0
			// the inner packetproto length is just the udpgw_header size (3)
			buf := make([]byte, 5)
			binary.LittleEndian.PutUint16(buf[0:2], 3)
			buf[2] = FlagKeepalive
			binary.LittleEndian.PutUint16(buf[3:5], 0) // keepalive usually uses conid 0 or current conid

			c.conn.Write(buf)
			c.mu.Unlock()
		}
	}
}

func (c *PacketConn) WriteTo(p []byte, addr net.Addr) (n int, err error) {
	udpAddr, ok := addr.(*net.UDPAddr)
	if !ok {
		return 0, errors.New("udpgw: invalid address type")
	}

	ip := udpAddr.IP.To4()
	isIPv6 := false
	if ip == nil {
		ip = udpAddr.IP.To16()
		isIPv6 = true
	}

	flags := uint8(0)
	if isIPv6 {
		flags |= FlagIPv6
	}

	// Set DNS flag if destination port is 53
	if udpAddr.Port == 53 {
		flags |= FlagDNS
	}

	c.mu.Lock()
	if c.firstSend {
		flags |= FlagRebind
		c.firstSend = false
	}
	c.mu.Unlock()

	// Calculate header length: flags(1) + conid(2) + ip + port(2)
	headerLen := 1 + 2 + len(ip) + 2
	payloadLen := len(p)
	totalLen := headerLen + payloadLen

	if totalLen > 0xFFFF {
		return 0, errors.New("udpgw: payload too large")
	}

	buf := make([]byte, 2+totalLen)

	// Length prefix (MUST be LittleEndian according to badvpn packetproto)
	binary.LittleEndian.PutUint16(buf[0:2], uint16(totalLen))

	// Header
	buf[2] = flags
	// conid MUST be LittleEndian
	binary.LittleEndian.PutUint16(buf[3:5], c.conid)

	// Address
	offset := 5
	copy(buf[offset:], ip)
	offset += len(ip)

	// Port (BigEndian/Network Order)
	binary.BigEndian.PutUint16(buf[offset:], uint16(udpAddr.Port))
	offset += 2

	// Payload
	copy(buf[offset:], p)

	c.mu.Lock()
	defer c.mu.Unlock()

	_, err = c.conn.Write(buf)
	if err != nil {
		return 0, err
	}

	return len(p), nil
}

func (c *PacketConn) ReadFrom(p []byte) (n int, addr net.Addr, err error) {
	for {
		// Read length prefix
		lenBuf := make([]byte, 2)
		if _, err := io.ReadFull(c.conn, lenBuf); err != nil {
			return 0, nil, err
		}
		totalLen := int(binary.LittleEndian.Uint16(lenBuf))

		if totalLen < 3 {
			return 0, nil, errors.New("udpgw: packet too short")
		}

		// Read packet body
		buf := make([]byte, totalLen)
		if _, err := io.ReadFull(c.conn, buf); err != nil {
			return 0, nil, err
		}

		flags := buf[0]
		// Ignore Keepalive replies by continuing the loop
		if (flags & FlagKeepalive) != 0 {
			continue
		}

		isIPv6 := (flags & FlagIPv6) != 0

		offset := 3 // Skip flags and conid
		var ip net.IP
		if isIPv6 {
			if totalLen < offset+18 {
				return 0, nil, errors.New("udpgw: packet too short for IPv6")
			}
			ip = net.IP(buf[offset : offset+16])
			offset += 16
		} else {
			if totalLen < offset+6 {
				return 0, nil, errors.New("udpgw: packet too short for IPv4")
			}
			ip = net.IP(buf[offset : offset+4])
			offset += 4
		}

		port := binary.BigEndian.Uint16(buf[offset : offset+2])
		offset += 2

		payloadLen := totalLen - offset
		if len(p) < payloadLen {
			return 0, nil, io.ErrShortBuffer
		}

		copy(p, buf[offset:])

		udpAddr := &net.UDPAddr{
			IP:   ip,
			Port: int(port),
		}

		return payloadLen, udpAddr, nil
	}
}

func (c *PacketConn) Close() error {
	c.cancel()
	return c.conn.Close()
}

func (c *PacketConn) LocalAddr() net.Addr {
	return c.conn.LocalAddr()
}

func (c *PacketConn) SetDeadline(t time.Time) error {
	return c.conn.SetDeadline(t)
}

func (c *PacketConn) SetReadDeadline(t time.Time) error {
	return c.conn.SetReadDeadline(t)
}

func (c *PacketConn) SetWriteDeadline(t time.Time) error {
	return c.conn.SetWriteDeadline(t)
}
