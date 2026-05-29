package config

import "os"

type Config struct {
	GRPCPort  string // Порт,
	AppiumURL string //  http://localhost:4723/wd/hub
}

func Load() *Config {
	grpcPort := os.Getenv("PARSE_GRPC_PORT")
	if grpcPort == "" {
		grpcPort = ":50051"
	}

	appiumURL := os.Getenv("APPIUM_URL")
	if appiumURL == "" {
		appiumURL = "http://localhost:4723/wd/hub"
	}

	return &Config{
		GRPCPort:  grpcPort,
		AppiumURL: appiumURL,
	}
}
