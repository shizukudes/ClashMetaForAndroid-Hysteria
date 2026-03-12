package udpgw

import (
	"encoding/binary"
	"errors"
	"io"
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
}

func NewPacketConn(conn net.Conn) *PacketConn {
	return &PacketConn{
		conn: conn,
	}
}

func (c *PacketConn) WriteTo(p []byte, addr net.Addr) (n int, err error) {
	udpAddr, ok := addr.(*net.UDPAddr)
	if !ok {
		return 0, errors.New("udpgw: invalid address type")
	}

	ip := udpAddr.IP
	isIPv6 := ip.To4() == nil
	if !isIPv6 {
		ip = ip.To4()
	}

	flags := uint8(0)
	if isIPv6 {
		flags |= FlagIPv6
	}

	// Calculate total length
	headerLen := 1 + 2 // flags (1) + conid (2)
	if isIPv6 {
		headerLen += 16 + 2 // ipv6 (16) + port (2)
	} else {
		headerLen += 4 + 2 // ipv4 (4) + port (2)
	}

	payloadLen := len(p)
	totalLen := headerLen + payloadLen

	if totalLen > 0xFFFF {
		return 0, errors.New("udpgw: payload too large")
	}

	buf := make([]byte, 2+totalLen)

	// Length (16-bit little-endian)
	binary.LittleEndian.PutUint16(buf[0:2], uint16(totalLen))

	// Header: flags and conid (using 0 for simplicity per TCP connection)
	buf[2] = flags
	binary.LittleEndian.PutUint16(buf[3:5], 0) // conid = 0

	// Address
	offset := 5
	copy(buf[offset:], ip)
	offset += len(ip)

	// Port (network byte order / big-endian)
	binary.BigEndian.PutUint16(buf[offset:offset+2], uint16(udpAddr.Port))
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
	// Read length (2 bytes, little-endian)
	lenBuf := make([]byte, 2)
	if _, err := io.ReadFull(c.conn, lenBuf); err != nil {
		return 0, nil, err
	}
	totalLen := int(binary.LittleEndian.Uint16(lenBuf))

	if totalLen < 3 {
		return 0, nil, errors.New("udpgw: packet too short")
	}

	// Read the rest of the packet
	buf := make([]byte, totalLen)
	if _, err := io.ReadFull(c.conn, buf); err != nil {
		return 0, nil, err
	}

	flags := buf[0]
	// conid := binary.LittleEndian.Uint16(buf[1:3]) // Ignored, we use 1 TCP conn per UDP session
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
