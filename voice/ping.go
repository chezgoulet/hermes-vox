package voice

import (
	"net/http"
	"time"
)

// Ping verifies the entity connection: GET /v1/models with the bearer must
// answer 200. Used by onboarding ("Connect & verify") — never fakes success.
func (c *HermesResponsesClient) Ping() error {
	req, err := http.NewRequest(http.MethodGet, c.baseURL+"/v1/models", nil)
	if err != nil {
		return err
	}
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return &statusError{status: resp.Status}
	}
	return nil
}

type statusError struct{ status string }

func (e *statusError) Error() string { return "hermes " + e.status }
