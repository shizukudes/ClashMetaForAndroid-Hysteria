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

	"github.com/metacubex/mihomo/log"
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
	if conn == nil {
		return nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	pc := &PacketConn{
		conn:      conn,
		conid:     uint16(rand.Intn(65535-1) + 1),
		firstSend: true,
		ctx:       ctx,
		cancel:    cancel,
	}

	go pc.keepaliveLoop()

	return pc
}

func (c *PacketConn) keepaliveLoop() {
	defer func() {
		if r := recover(); r != nil {
			log.Debugln("[UDPGW] keepaliveLoop panic: %v", r)
		}
	}()

	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-c.ctx.Done():
			return
		case <-ticker.C:
			c.mu.Lock()
			if c.conn != nil {
				buf := make([]byte, 5)
				binary.LittleEndian.PutUint16(buf[0:2], 3)
				buf[2] = FlagKeepalive
				binary.LittleEndian.PutUint16(buf[3:5], 0)
				c.conn.Write(buf)
			}
			c.mu.Unlock()
		}
	}
}

func (c *PacketConn) WriteTo(p []byte, addr net.Addr) (n int, err error) {
	defer func() {
		if r := recover(); r != nil {
			log.Debugln("[UDPGW] WriteTo panic: %v", r)
			err = errors.New("udpgw panic")
		}
	}()

	if c == nil || c.conn == nil {
		return 0, errors.New("udpgw: connection closed")
	}

	udpAddr, ok := addr.(*net.UDPAddr)
	if !ok || udpAddr == nil {
		return 0, errors.New("udpgw: invalid address type")
	}

	if udpAddr.IP == nil {
		return 0, errors.New("udpgw: nil IP address")
	}

	ip := udpAddr.IP.To4()
	isIPv6 := false
	if ip == nil {
		ip = udpAddr.IP.To16()
		isIPv6 = true
	}

	if ip == nil {
		return 0, errors.New("udpgw: unable to parse IP")
	}

	flags := uint8(0)
	if isIPv6 {
		flags |= FlagIPv6
	}

	if udpAddr.Port == 53 {
		flags |= FlagDNS
	}

	c.mu.Lock()
	if c.firstSend {
		flags |= FlagRebind
		c.firstSend = false
	}
	c.mu.Unlock()

	headerLen := 1 + 2 + len(ip) + 2
	payloadLen := len(p)
	totalLen := headerLen + payloadLen

	if totalLen > 0xFFFF {
		return 0, errors.New("udpgw: payload too large")
	}

	buf := make([]byte, 2+totalLen)

	binary.LittleEndian.PutUint16(buf[0:2], uint16(totalLen))

	buf[2] = flags
	binary.LittleEndian.PutUint16(buf[3:5], c.conid)

	offset := 5
	copy(buf[offset:], ip)
	offset += len(ip)

	binary.BigEndian.PutUint16(buf[offset:], uint16(udpAddr.Port))
	offset += 2

	copy(buf[offset:], p)

	c.mu.Lock()
	defer c.mu.Unlock()

	_, err = c.conn.Write(buf)
	if err != nil {
		log.Debugln("[UDPGW] WriteTo failed: %v", err)
		return 0, err
	}

	log.Debugln("[UDPGW] Sent %d bytes to %s via conid %d (flags: %d)", payloadLen, addr.String(), c.conid, flags)
	return len(p), nil
}

func (c *PacketConn) ReadFrom(p []byte) (n int, addr net.Addr, err error) {
	defer func() {
		if r := recover(); r != nil {
			log.Debugln("[UDPGW] ReadFrom panic: %v", r)
			err = errors.New("udpgw panic")
		}
	}()

	if c == nil || c.conn == nil {
		return 0, nil, errors.New("udpgw: connection closed")
	}

	for {
		lenBuf := make([]byte, 2)
		if _, err := io.ReadFull(c.conn, lenBuf); err != nil {
			return 0, nil, err
		}
		totalLen := int(binary.LittleEndian.Uint16(lenBuf))

		if totalLen < 3 {
			return 0, nil, errors.New("udpgw: packet too short")
		}

		buf := make([]byte, totalLen)
		if _, err := io.ReadFull(c.conn, buf); err != nil {
			return 0, nil, err
		}

		flags := buf[0]
		if (flags & FlagKeepalive) != 0 {
			continue
		}

		isIPv6 := (flags & FlagIPv6) != 0

		offset := 3
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

		log.Debugln("[UDPGW] Received %d bytes from %s via conid %d", payloadLen, udpAddr.String(), binary.LittleEndian.Uint16(buf[1:3]))
		return payloadLen, udpAddr, nil
	}
}

func (c *PacketConn) Close() error {
	if c != nil && c.cancel != nil {
		c.cancel()
	}
	if c != nil && c.conn != nil {
		return c.conn.Close()
	}
	return nil
}

func (c *PacketConn) LocalAddr() net.Addr {
	if c == nil || c.conn == nil {
		return nil
	}
	return c.conn.LocalAddr()
}

func (c *PacketConn) SetDeadline(t time.Time) error {
	if c == nil || c.conn == nil {
		return errors.New("closed")
	}
	return c.conn.SetDeadline(t)
}

func (c *PacketConn) SetReadDeadline(t time.Time) error {
	if c == nil || c.conn == nil {
		return errors.New("closed")
	}
	return c.conn.SetReadDeadline(t)
}

func (c *PacketConn) SetWriteDeadline(t time.Time) error {
	if c == nil || c.conn == nil {
		return errors.New("closed")
	}
	return c.conn.SetWriteDeadline(t)
}
