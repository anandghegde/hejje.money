package config

import (
	"os"
	"path/filepath"

	"gopkg.in/yaml.v3"
)

// Config is the TUI configuration. The API key is never stored in the file; it comes from HEJJE_API_KEY.
type Config struct {
	ServerURL string `yaml:"server_url"`
	APIKey    string `yaml:"-"`
}

// Load reads ~/.config/hejje/config.yaml (if present) and the HEJJE_API_KEY environment variable.
func Load() (Config, error) {
	cfg := Config{ServerURL: "http://localhost:8080"}
	path := configPath()
	if data, err := os.ReadFile(path); err == nil {
		if err := yaml.Unmarshal(data, &cfg); err != nil {
			return cfg, err
		}
	}
	if env := os.Getenv("HEJJE_SERVER_URL"); env != "" {
		cfg.ServerURL = env
	}
	cfg.APIKey = os.Getenv("HEJJE_API_KEY")
	return cfg, nil
}

func configPath() string {
	if xdg := os.Getenv("XDG_CONFIG_HOME"); xdg != "" {
		return filepath.Join(xdg, "hejje", "config.yaml")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "config.yaml"
	}
	return filepath.Join(home, ".config", "hejje", "config.yaml")
}
