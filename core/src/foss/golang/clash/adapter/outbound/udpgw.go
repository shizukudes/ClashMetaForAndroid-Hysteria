package outbound

import (
	"context"
	"fmt"
	"net"
	"strconv"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/transport/udpgw"
)

type UdpGw struct {
	*Base
	option *UdpGwOption
}

type UdpGwOption struct {
	BasicOption
	Name   string `proxy:"name"`
	Server string `proxy:"server"`
	Port   int    `proxy:"port"`
	UDP    bool   `proxy:"udp,omitempty"`
}

// StreamConnContext implements C.ProxyAdapter
func (u *UdpGw) StreamConnContext(ctx context.Context, c net.Conn, metadata *C.Metadata) (net.Conn, error) {
	return nil, fmt.Errorf("udpgw does not support tcp")
}

// DialContext implements C.ProxyAdapter
func (u *UdpGw) DialContext(ctx context.Context, metadata *C.Metadata) (_ C.Conn, err error) {
	return nil, fmt.Errorf("udpgw does not support tcp")
}

// ListenPacketContext implements C.ProxyAdapter
func (u *UdpGw) ListenPacketContext(ctx context.Context, metadata *C.Metadata) (_ C.PacketConn, err error) {
	c, err := u.dialer.DialContext(ctx, "tcp", u.addr)
	if err != nil {
		return nil, fmt.Errorf("%s connect error: %w", u.addr, err)
	}

	pc := udpgw.NewPacketConn(c)
	return NewPacketConn(pc, u), nil
}

func NewUdpGw(option UdpGwOption) (*UdpGw, error) {
	addr := net.JoinHostPort(option.Server, strconv.Itoa(option.Port))
	return &UdpGw{
		Base: &Base{
			name:   option.Name,
			addr:   addr,
			tp:     C.UdpGw,
			udp:    true,
			tfo:    false,
			rmark:  option.RoutingMark,
			dialer: option.DialerForAPI,
		},
		option: &option,
	}, nil
}
