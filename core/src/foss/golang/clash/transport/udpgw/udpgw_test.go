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

func (c *rwConn) Read(p []byte) (int, error)         { return c.r.Read(p) }
func (c *rwConn) Write(p []byte) (int, error)        { return c.w.Write(p) }
func (c *rwConn) Close() error                       { return nil }
func (c *rwConn) LocalAddr() net.Addr                { return mockAddr("local") }
func (c *rwConn) RemoteAddr() net.Addr               { return mockAddr("remote") }
func (c *rwConn) SetDeadline(time.Time) error        { return nil }
func (c *rwConn) SetReadDeadline(time.Time) error    { return nil }
func (c *rwConn) SetWriteDeadline(time.Time) error   { return nil }

func TestWriteToUsesLittleEndianPacketProtoAndPort(t *testing.T) {
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
	gotPort := binary.LittleEndian.Uint16(frame[portOff : portOff+2])
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
}

func TestReadFromParsesLittleEndianFrame(t *testing.T) {
	payload := []byte{0x10, 0x20}
	packetLen := 1 + 2 + 4 + 2 + len(payload)
	frame := make([]byte, 2+packetLen)
	binary.LittleEndian.PutUint16(frame[:2], uint16(packetLen))
	frame[2] = 0 // flags
	binary.LittleEndian.PutUint16(frame[3:5], 99)
	copy(frame[5:9], []byte{9, 9, 9, 9})
	binary.LittleEndian.PutUint16(frame[9:11], 3478)
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
