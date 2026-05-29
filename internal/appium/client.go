package appium

import (
	"fmt"
	"time"

	"github.com/notshtrikhcode/android-agent/internal/config"

	"github.com/electricbubble/gadb"
	"github.com/tebeka/selenium"
)

// StartAppSession запускает приложение через gadb и поднимает сессию Appium
func StartAppSession(cfg *config.Config, appPackage, appActivity string) (selenium.WebDriver, error) {
	// 1. Сначала принудительно перезапускаем приложение через ADB
	adbClient, err := gadb.NewClient()
	if err == nil {
		devices, _ := adbClient.DeviceList()
		if len(devices) > 0 {
			_, _ = devices[0].RunShellCommand("am force-stop " + appPackage)
			time.Sleep(300 * time.Millisecond)
			_, _ = devices[0].RunShellCommand("am start -n " + appPackage + "/" + appActivity)
			time.Sleep(6 * time.Second) // Даем время на загрузку splash-screen
		}
	}

	// 2. Настраиваем Capabilities для Appium
	caps := selenium.Capabilities{
		"platformName":             "Android",
		"appium:automationName":    "UiAutomator2",
		"appium:deviceName":        "Android",
		"appium:appPackage":        appPackage,
		"appium:appActivity":       appActivity,
		"appium:noReset":           true,
		"appium:newCommandTimeout": 300,
	}

	driver, err := selenium.NewRemote(caps, cfg.AppiumURL)
	if err != nil {
		return nil, fmt.Errorf("ошибка подключения к Appium: %w", err)
	}

	return driver, nil
}
