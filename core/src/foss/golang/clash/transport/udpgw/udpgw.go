package udpgw

import (
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
	conn net.Conn
	mu   sync.Mutex

	nextConID uint16
	addrConID map[string]uint16
	usedConID map[uint16]struct{}
}

func NewPacketConn(conn net.Conn) *PacketConn {
	start := uint16(rand.Intn(65535-1) + 1)
	return &PacketConn{
		conn:      conn,
		nextConID: start,
		addrConID: map[string]uint16{},
		usedConID: map[uint16]struct{}{},
	}
}

func (c *PacketConn) conIDForAddr(addr *net.UDPAddr) uint16 {
	key := addr.String()
	if conid, ok := c.addrConID[key]; ok {
		return conid
	}

	for {
		if c.nextConID == 0 {
			c.nextConID = 1
		}
		if _, exists := c.usedConID[c.nextConID]; !exists {
			conid := c.nextConID
			c.usedConID[conid] = struct{}{}
			c.addrConID[key] = conid
			c.nextConID++
			return conid
		}
		c.nextConID++
	}
}

func (c *PacketConn) WriteTo(p []byte, addr net.Addr) (n int, err error) {
	c.mu.Lock()
	defer c.mu.Unlock()

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

	conid := c.conIDForAddr(udpAddr)

	// Calculate header length: flags(1) + conid(2) + ip + port(2)
	headerLen := 1 + 2 + len(ip) + 2
	payloadLen := len(p)
	totalLen := headerLen + payloadLen

	if totalLen > 0xFFFF {
		return 0, errors.New("udpgw: payload too large")
	}

	buf := make([]byte, 2+totalLen)

	// PacketProto length prefix is little-endian.
	binary.LittleEndian.PutUint16(buf[0:2], uint16(totalLen))

	// Header
	buf[2] = flags
	// conid is LittleEndian in original badvpn
	binary.LittleEndian.PutUint16(buf[3:5], conid)

	// Address
	offset := 5
	copy(buf[offset:], ip)
	offset += len(ip)

	// UDPGW protocol fields are little-endian.
	binary.LittleEndian.PutUint16(buf[offset:], uint16(udpAddr.Port))
	offset += 2

	// Payload
	copy(buf[offset:], p)

	_, err = c.conn.Write(buf)
	if err != nil {
		return 0, err
	}

	return len(p), nil
}

func (c *PacketConn) ReadFrom(p []byte) (n int, addr net.Addr, err error) {
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

	port := binary.LittleEndian.Uint16(buf[offset : offset+2])
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

func (c *PacketConn) Close() error {
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
