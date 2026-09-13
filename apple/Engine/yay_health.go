// SPDX-License-Identifier: GPL-3.0-or-later
// Copied into the pinned sing-box experimental/libbox package by build-apple-core.sh.
package libbox

import (
    "context"
    "fmt"
    "net"
    "net/http"
    "time"
    M "github.com/sagernet/sing/common/metadata"
)

// YayCheckConnection checks the authenticated proxy outbound, never a direct URLSession.
func (s *BoxService) YayCheckConnection() error {
    outbound, ok := s.instance.Outbound().Outbound("proxy")
    if !ok { return fmt.Errorf("proxy outbound missing") }
    ctx, cancel := context.WithTimeout(s.ctx, 8*time.Second)
    defer cancel()
    transport := &http.Transport{DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
        return outbound.DialContext(ctx, network, M.ParseSocksaddr(address))
    }}
    defer transport.CloseIdleConnections()
    client := &http.Client{Transport: transport, Timeout: 8*time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
    request, err := http.NewRequestWithContext(ctx, "GET", "https://www.gstatic.com/generate_204", nil)
    if err != nil { return err }
    response, err := client.Do(request)
    if err != nil { return err }
    defer response.Body.Close()
    if response.StatusCode != http.StatusNoContent { return fmt.Errorf("VPN internet check returned %d", response.StatusCode) }
    return nil
}
