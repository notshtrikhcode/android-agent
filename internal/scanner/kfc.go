package scanner

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/tebeka/selenium"
)

const (
	KfcPackage    = "ru.kfc.kfc_delivery"
	KfcActivity   = "com.kfc.ui.activities.NativeModuleActivity"
	KfcMaxScrolls = 90 // Максимальное количество быстрых скроллов
)

// ScanKFC выполняет автоматизацию KFC и возвращает сырой XML-дамп всего меню
func (s *PhoneUiServer) ScanKFC(ctx context.Context) (string, error) {
	fmt.Println("🚀 [KFC] APPIUM ULTRA-FAST AGGREGATOR STARTED FOR KFC...")

	// 1. Инициализация Appium сессии (Capabilities из твоего рабочего сканера)
	caps := selenium.Capabilities{
		"platformName":              "Android",
		"appium:automationName":     "UiAutomator2",
		"appium:deviceName":         "Android",
		"appium:appPackage":         KfcPackage,
		"appium:appActivity":        KfcActivity,
		"appium:forceAppLaunch":     true, // Заставляет явно пнуть Activity
		"appium:shouldTerminateApp": true, // Убивает висящий процесс перед стартом
		"appium:noReset":            true,
		"appium:newCommandTimeout":  300,
	}

	driver, err := selenium.NewRemote(caps, s.cfg.AppiumURL)
	if err != nil {
		return "", fmt.Errorf("ошибка подключения к Appium для KFC: %w", err)
	}
	defer driver.Quit()

	fmt.Println("✅ [KFC] Connected to Appium. Waiting for app load...")
	time.Sleep(4 * time.Second)

	fmt.Println("👆 [KFC] Делаем проверочный клик в центр экрана...")
	_, err = driver.ExecuteScript("mobile: clickGesture", []interface{}{
		map[string]interface{}{
			"x": 540,
			"y": 1110,
		},
	})
	if err != nil {
		fmt.Printf("⚠️ [KFC] Не удалось тапнуть: %v\n", err)
	}
	time.Sleep(4 * time.Second) // Ожидание закрытия возможных баннеров

	// 2. Цикл ультра-разгона скроллинга и накопления XML
	var xmlBuilder strings.Builder
	xmlBuilder.WriteString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<menu_dump>\n")

	// Параметры быстрого драга, которые ты подобрали: снизу вверх, мгновенная остановка
	dragParams := map[string]interface{}{
		"startX": 540,
		"startY": 1600, // Почти самый низ экрана
		"endX":   540,
		"endY":   350,  // Почти самый верх экрана
		"speed":  2500, // Высокая скорость перемещения
	}

	for scroll := 0; scroll < KfcMaxScrolls; scroll++ {
		fmt.Printf("📸 [KFC] Снятие дампа экрана %d/%d... ", scroll+1, KfcMaxScrolls)
		start := time.Now()

		rawXML, err := driver.PageSource()
		if err != nil {
			fmt.Printf("\n⚠️ [KFC] Ошибка получения страницы: %v\n", err)
			break
		}

		if len(rawXML) < 100 {
			fmt.Println("\n⚠️ [KFC] Слишком короткий ответ, возможно конец списка.")
			break
		}

		// Вычищаем тег <hierarchy>, оставляя только внутренние ноды
		content := extractInnerXML(rawXML)

		// Упаковываем во внутренний XML-контейнер экрана
		xmlBuilder.WriteString(fmt.Sprintf("  <screen index=\"%d\">\n", scroll+1))
		xmlBuilder.WriteString(content)
		xmlBuilder.WriteString("\n  </screen>\n")

		fmt.Printf("Снято за %v. Скроллим... ", time.Since(start))

		// Выполняем dragGesture (передаем обернутым в []interface{}{})
		_, err = driver.ExecuteScript("mobile: dragGesture", []interface{}{dragParams})
		if err != nil {
			fmt.Printf("🏁 [KFC] Конец меню или ошибка скролла: %v\n", err)
			break
		}

		// Минимальная пауза, так как drag останавливает список мгновенно
		time.Sleep(80 * time.Millisecond)
		fmt.Println("OK")
	}

	xmlBuilder.WriteString("</menu_dump>\n")

	// Возвращаем собранную XML-цепочку
	return xmlBuilder.String(), nil
}

// extractInnerXML вырезает внешнюю обертку <hierarchy>, чтобы Scanner-сервис получил чистые ноды
func extractInnerXML(rawXML string) string {
	if idx := strings.Index(rawXML, "<?xml"); idx != -1 {
		rawXML = rawXML[idx:]
	}

	startIdx := strings.Index(rawXML, "<hierarchy")
	if startIdx == -1 {
		return rawXML
	}

	closeBracketIdx := strings.Index(rawXML[startIdx:], ">")
	if closeBracketIdx == -1 {
		return rawXML
	}
	actualStart := startIdx + closeBracketIdx + 1

	endIdx := strings.LastIndex(rawXML, "</hierarchy>")
	if endIdx == -1 {
		return rawXML[actualStart:]
	}

	return strings.TrimSpace(rawXML[actualStart:endIdx])
}
