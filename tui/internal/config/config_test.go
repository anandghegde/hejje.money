package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadReadsFileAndApiKeyFromEnv(t *testing.T) {
	dir := t.TempDir()
	cfgDir := filepath.Join(dir, "hejje")
	if err := os.MkdirAll(cfgDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfgDir, "config.yaml"), []byte("server_url: http://example:9000\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("HEJJE_API_KEY", "hejje_secret_key")

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ServerURL != "http://example:9000" {
		t.Fatalf("server url %q", cfg.ServerURL)
	}
	if cfg.APIKey != "hejje_secret_key" {
		t.Fatalf("api key %q", cfg.APIKey)
	}
}

func TestApiKeyNeverComesFromFile(t *testing.T) {
	dir := t.TempDir()
	cfgDir := filepath.Join(dir, "hejje")
	_ = os.MkdirAll(cfgDir, 0o755)
	// even if someone puts api_key in the file, it must be ignored (secrets come from the environment only)
	_ = os.WriteFile(filepath.Join(cfgDir, "config.yaml"), []byte("server_url: http://x\napi_key: LEAKED\n"), 0o644)
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("HEJJE_API_KEY", "")

	cfg, _ := Load()
	if cfg.APIKey == "LEAKED" {
		t.Fatal("api key must never be read from the config file")
	}
}
