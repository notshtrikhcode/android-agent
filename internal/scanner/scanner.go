package scanner

import (
	"context"
	"fmt"
	"log"
	"sync"

	"github.com/notshtrikhcode/android-agent/internal/config"
	"github.com/notshtrikhcode/android-agent/pb"
)

type PhoneUiServer struct {
	pb.UnimplementedPhoneUiServiceServer
	cfg *config.Config
	mu  sync.Mutex // Тот самый мьютекс, защищающий телефон от параллельных кликов
}

func NewPhoneUiServer(cfg *config.Config) *PhoneUiServer {
	return &PhoneUiServer{
		cfg: cfg,
	}
}

// GetRawMenuDump имплементирует сгенерированный gRPC интерфейс
func (s *PhoneUiServer) GetRawMenuDump(ctx context.Context, req *pb.MenuRequest) (*pb.MenuResponse, error) {
	log.Printf("📱 [gRPC] Получен запрос на сбор дампа для: %s", req.Provider)

	// Блокируем телефон. Пока функция не завершится, никто другой к телефону не прикоснется
	s.mu.Lock()
	defer s.mu.Unlock()

	var rawXML string
	var err error

	// Перенаправляем логику в изолированные файлы автоматизации
	switch req.Provider {
	case "kfc":
		rawXML, err = s.ScanKFC(ctx)
	case "mac":
		rawXML, err = s.ScanMac(ctx)
	default:
		return nil, fmt.Errorf("неизвестный провайдер меню: %s", req.Provider)
	}

	if err != nil {
		log.Printf("❌ Ошибка при автоматизации %s: %v", req.Provider, err)
		return nil, err
	}

	log.Printf("✅ Дамп для %s успешно собран (размер: %d символов). Отправляем обратно...", req.Provider, len(rawXML))
	return &pb.MenuResponse{RawXml: rawXML}, nil
}
