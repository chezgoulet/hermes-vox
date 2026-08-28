package voice

import (
	"fmt"
	"io"
	"net/http"
	"time"
)

// HermesGatewayClient talks to the Hermes gateway's model/health/session
// endpoints that the app's on-device catalog CANNOT provide: /api/model/options
// (the full provider/model catalog the /models native chooser reads) and
// /v1/health (the status/version card). Same baseURL + bearer key as
// HermesResponsesClient. The /models chooser reads the catalog, then the app
// sends the chosen provider+model per-request in /v1/responses (the blessed
// override) — the entity's memory/skills/context are preserved.
type HermesGatewayClient struct {
	baseURL string
	apiKey  string
	http    *http.Client
}

// NewHermesGatewayClient builds the gateway client (same auth as the responses client).
func NewHermesGatewayClient(baseURL, apiKey string) *HermesGatewayClient {
	return &HermesGatewayClient{
		baseURL: baseURL,
		apiKey:  apiKey,
		http:    &http.Client{Timeout: 20 * time.Second},
	}
}

func (g *HermesGatewayClient) get(path string) (string, int, error) {
	req, err := http.NewRequest(http.MethodGet, g.baseURL+path, nil)
	if err != nil {
		return "", 0, err
	}
	if g.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+g.apiKey)
	}
	resp, err := g.http.Do(req)
	if err != nil {
		return "", 0, err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return string(b), resp.StatusCode, nil
}

// ModelOptions returns the raw /api/model/options JSON
// ({providers:[{slug,name,models[],total_models,is_current,authenticated,source,capabilities,warning}]}).
func (g *HermesGatewayClient) ModelOptions() (string, error) {
	b, code, err := g.get("/api/model/options")
	if err != nil {
		return "", err
	}
	if code != 200 {
		return "", fmt.Errorf("hermes model options %d", code)
	}
	return b, nil
}

// Health returns the raw /v1/health JSON (status/version).
func (g *HermesGatewayClient) Health() (string, error) {
	b, code, err := g.get("/v1/health")
	if err != nil {
		return "", err
	}
	if code != 200 {
		return "", fmt.Errorf("hermes health %d", code)
	}
	return b, nil
}

// DeleteResponse drops a stored server-side response (the /new session reset).
func (g *HermesGatewayClient) DeleteResponse(id string) error {
	req, err := http.NewRequest(http.MethodDelete, g.baseURL+"/v1/responses/"+id, nil)
	if err != nil {
		return err
	}
	if g.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+g.apiKey)
	}
	resp, err := g.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 && resp.StatusCode != 204 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("hermes delete response %s: %s", resp.Status, string(b))
	}
	return nil
}
