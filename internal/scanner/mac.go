package scanner

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/electricbubble/gadb"
	"github.com/tebeka/selenium"
)

const (
	MacPackage    = "com.apegroup.mcdonaldsrussia"
	MacActivity   = "com.apegroup.mcdonaldsrussia.activities.main.MainActivity"
	MacMaxScrolls = 60
)

// RunMacAutomation выполняет неубиваемый сценарий сбора XML для Вкусно и Точка
func (r *PhoneUiServer) ScanMac(ctx context.Context) (string, error) {
	fmt.Println("🚀 [Mac] ЗАПУСК НЕУБИВАЕМОГО СКАНЕРА МЕНЮ ВКУСНО И ТОЧКА...")

	// 1. Чистый запуск приложения через GADB
	adbClient, err := gadb.NewClient()
	if err == nil {
		devices, err := adbClient.DeviceList()
		if err == nil && len(devices) > 0 {
			// Принудительно тушим старый процесс
			_, _ = devices[0].RunShellCommand("am force-stop " + MacPackage)
			time.Sleep(500 * time.Millisecond)

			// Запускаем заново
			_, _ = devices[0].RunShellCommand("am start -n " + MacPackage + "/" + MacActivity)
			fmt.Println("⏳ [Mac] Ожидаем загрузки приложения (7 сек)...")
			time.Sleep(7 * time.Second)
		} else {
			fmt.Println("⚠️ [Mac] ADB устройства не найдены, пробуем продолжить через Appium...")
		}
	} else {
		fmt.Printf("⚠️ [Mac] Не удалось подключиться к GADB: %v. Пробуем только через Appium...\n", err)
	}

	// 2. Инициализация Appium сессии (переменная r.cfg.AppiumURL берется из твоего конфига)
	caps := selenium.Capabilities{
		"platformName":             "Android",
		"appium:automationName":    "UiAutomator2",
		"appium:deviceName":        "Android",
		"appium:appPackage":        MacPackage,
		"appium:appActivity":       MacActivity,
		"appium:noReset":           true,
		"appium:newCommandTimeout": 300,
	}

	driver, err := selenium.NewRemote(caps, r.cfg.AppiumURL)
	if err != nil {
		return "", fmt.Errorf("ошибка подключения к Appium для Mac: %w", err)
	}
	defer driver.Quit()

	// 3. Переход во вкладку "Меню"
	fmt.Println("👇 [Mac] Переходим во вкладку 'Меню'...")
	menuTab, err := driver.FindElement(selenium.ByID, "com.apegroup.mcdonaldsrussia:id/menuMenu")
	if err != nil {
		fmt.Println("⚠️ [Mac] ID menuMenu не найден. Пробуем клик по дефолтным координатам нижнего бара...")
		_, _ = driver.ExecuteScript("mobile: clickGesture", []interface{}{
			map[string]interface{}{"x": 540, "y": 2100},
		})
	} else {
		_ = menuTab.Click()
	}
	time.Sleep(3 * time.Second)

	// 4. Цикл скроллинга и накопления XML в strings.Builder
	var combinedBuilder strings.Builder
	combinedBuilder.WriteString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<menu_dump>\n")

	for i := 0; i < MacMaxScrolls; i++ {
		fmt.Printf("📸 [Mac] Снимаем полный дамп экрана %d/%d...\n", i+1, MacMaxScrolls)

		rawXML, err := driver.PageSource()
		if err != nil {
			fmt.Printf("⚠️ [Mac] Ошибка получения PageSource: %v\n", err)
			break
		}

		// Твоя фирменная чистка XML-заголовков, чтобы они не дублировались внутри тегов screen
		cleanXML := strings.Replace(rawXML, "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>", "", 1)
		cleanXML = strings.Replace(cleanXML, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "", 1)
		cleanXML = strings.TrimSpace(cleanXML)

		// Упаковываем текущий экран во внутренний XML-контейнер
		combinedBuilder.WriteString(fmt.Sprintf("  <screen index=\"%d\">\n", i+1))
		combinedBuilder.WriteString(cleanXML)
		combinedBuilder.WriteString("\n  </screen>\n")

		// Твой проверенный жест скролла dragGesture
		_, err = driver.ExecuteScript("mobile: dragGesture", []interface{}{
			map[string]interface{}{
				"startX": 540,
				"startY": 1600, // Чуть ниже центра дисплея
				"endX":   540,
				"endY":   400, // Тянем вверх
				"speed":  2500,
			},
		})
		if err != nil {
			fmt.Printf("🏁 [Mac] Достигнут конец списка или ошибка скролла: %v\n", err)
			break
		}

		// Короткая пауза для затухания анимации интерфейса
		time.Sleep(200 * time.Millisecond)
	}

	combinedBuilder.WriteString("</menu_dump>\n")

	// Возвращаем всю собранную XML-строку наверх, чтобы её можно было отправить по gRPC
	return combinedBuilder.String(), nil
}
