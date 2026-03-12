package udpgw

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

type mockAddr string

func (a mockAddr) Network() string { return "mock" }
func (a mockAddr) String() string  { return string(a) }

type rwConn struct {
	r io.Reader
	w *bytes.Buffer
}

func (c *rwConn) Read(p []byte) (int, error)       { return c.r.Read(p) }
func (c *rwConn) Write(p []byte) (int, error)      { return c.w.Write(p) }
func (c *rwConn) Close() error                     { return nil }
func (c *rwConn) LocalAddr() net.Addr              { return mockAddr("local") }
func (c *rwConn) RemoteAddr() net.Addr             { return mockAddr("remote") }
func (c *rwConn) SetDeadline(time.Time) error      { return nil }
func (c *rwConn) SetReadDeadline(time.Time) error  { return nil }
func (c *rwConn) SetWriteDeadline(time.Time) error { return nil }

func TestWriteToUsesLittleEndianPacketProtoAndBigEndianPort(t *testing.T) {
	out := &bytes.Buffer{}
	pc := NewPacketConn(&rwConn{r: bytes.NewReader(nil), w: out})

	payload := []byte{0xAA, 0xBB, 0xCC}
	addr := &net.UDPAddr{IP: net.IPv4(1, 2, 3, 4), Port: 3478}
	if _, err := pc.WriteTo(payload, addr); err != nil {
		t.Fatalf("WriteTo failed: %v", err)
	}

	frame := out.Bytes()
	if len(frame) < 2 {
		t.Fatalf("short frame: %d", len(frame))
	}

	declaredLen := int(binary.LittleEndian.Uint16(frame[:2]))
	if declaredLen != len(frame)-2 {
		t.Fatalf("invalid little-endian frame len, got %d want %d", declaredLen, len(frame)-2)
	}

	portOff := 2 + 1 + 2 + 4
	gotPort := binary.BigEndian.Uint16(frame[portOff : portOff+2])
	if gotPort != uint16(addr.Port) {
		t.Fatalf("port encoded with wrong endianness: got %d want %d", gotPort, addr.Port)
	}
}

func TestWriteToUsesStableConIDPerRemote(t *testing.T) {
	out := &bytes.Buffer{}
	pc := NewPacketConn(&rwConn{r: bytes.NewReader(nil), w: out})

	addrA := &net.UDPAddr{IP: net.IPv4(8, 8, 8, 8), Port: 53}
	addrB := &net.UDPAddr{IP: net.IPv4(1, 1, 1, 1), Port: 53}

	if _, err := pc.WriteTo([]byte{0x01}, addrA); err != nil {
		t.Fatalf("write A1: %v", err)
	}
	if _, err := pc.WriteTo([]byte{0x02}, addrA); err != nil {
		t.Fatalf("write A2: %v", err)
	}
	if _, err := pc.WriteTo([]byte{0x03}, addrB); err != nil {
		t.Fatalf("write B1: %v", err)
	}

	frame := out.Bytes()
	p := 0
	var ids []uint16
	for p+2 <= len(frame) {
		l := int(binary.LittleEndian.Uint16(frame[p : p+2]))
		if p+2+l > len(frame) {
			t.Fatalf("broken frame length")
		}
		ids = append(ids, binary.LittleEndian.Uint16(frame[p+3:p+5]))
		p += 2 + l
	}

	if len(ids) != 3 {
		t.Fatalf("expected 3 packets, got %d", len(ids))
	}
	if ids[0] != ids[1] {
		t.Fatalf("same remote must reuse conid, got %d and %d", ids[0], ids[1])
	}
	if ids[2] == ids[0] {
		t.Fatalf("different remote should use different conid, got same %d", ids[2])
	}

	flagAt := func(frame []byte, idx int) uint8 {
		p := 0
		for i := 0; i < idx; i++ {
			l := int(binary.LittleEndian.Uint16(frame[p : p+2]))
			p += 2 + l
		}
		return frame[p+2]
	}

	if (flagAt(frame, 0) & FlagRebind) == 0 {
		t.Fatalf("first packet for new remote must set REBIND")
	}
	if (flagAt(frame, 1) & FlagRebind) != 0 {
		t.Fatalf("subsequent packet for same remote must not set REBIND")
	}
	if (flagAt(frame, 2) & FlagRebind) == 0 {
		t.Fatalf("first packet for another new remote must set REBIND")
	}
}

func TestReadFromParsesLittleEndianFrame(t *testing.T) {
	payload := []byte{0x10, 0x20}
	packetLen := 1 + 2 + 4 + 2 + len(payload)
	frame := make([]byte, 2+packetLen)
	binary.LittleEndian.PutUint16(frame[:2], uint16(packetLen))
	frame[2] = 0 // flags
	binary.LittleEndian.PutUint16(frame[3:5], 99)
	copy(frame[5:9], []byte{9, 9, 9, 9})
	binary.BigEndian.PutUint16(frame[9:11], 3478)
	copy(frame[11:], payload)

	pc := NewPacketConn(&rwConn{r: bytes.NewReader(frame), w: &bytes.Buffer{}})
	buf := make([]byte, 16)
	n, addr, err := pc.ReadFrom(buf)
	if err != nil {
		t.Fatalf("ReadFrom failed: %v", err)
	}

	if got := buf[:n]; !bytes.Equal(got, payload) {
		t.Fatalf("payload mismatch got=%v want=%v", got, payload)
	}
	udpAddr, ok := addr.(*net.UDPAddr)
	if !ok {
		t.Fatalf("addr type = %T", addr)
	}
	if !udpAddr.IP.Equal(net.IPv4(9, 9, 9, 9)) || udpAddr.Port != 3478 {
		t.Fatalf("addr mismatch: %v", udpAddr)
	}
}

func TestReadFromSkipsKeepaliveFrame(t *testing.T) {
	payload := []byte{0xCA, 0xFE}

	keepalive := make([]byte, 2+3)
	binary.LittleEndian.PutUint16(keepalive[:2], 3)
	keepalive[2] = FlagKeepalive
	binary.LittleEndian.PutUint16(keepalive[3:5], 1)

	dataLen := 1 + 2 + 4 + 2 + len(payload)
	data := make([]byte, 2+dataLen)
	binary.LittleEndian.PutUint16(data[:2], uint16(dataLen))
	data[2] = 0
	binary.LittleEndian.PutUint16(data[3:5], 1)
	copy(data[5:9], []byte{7, 7, 7, 7})
	binary.BigEndian.PutUint16(data[9:11], 443)
	copy(data[11:], payload)

	stream := append(keepalive, data...)
	pc := NewPacketConn(&rwConn{r: bytes.NewReader(stream), w: &bytes.Buffer{}})

	buf := make([]byte, 16)
	n, addr, err := pc.ReadFrom(buf)
	if err != nil {
		t.Fatalf("ReadFrom failed: %v", err)
	}
	if !bytes.Equal(buf[:n], payload) {
		t.Fatalf("payload mismatch got=%v want=%v", buf[:n], payload)
	}
	udpAddr, ok := addr.(*net.UDPAddr)
	if !ok || udpAddr.Port != 443 {
		t.Fatalf("invalid addr: %v", addr)
	}
}
