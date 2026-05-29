package main

import (
	"log"
	"net"

	"github.com/notshtrikhcode/android-agent/internal/config"
	"github.com/notshtrikhcode/android-agent/internal/scanner"
	"github.com/notshtrikhcode/android-agent/pb"
	"google.golang.org/grpc"
)

func main() {
	log.Println("⚡ Инициализация локального Parse Service...")

	// 1. Загружаем конфиги
	cfg := config.Load()

	// 2. Открываем TCP порт
	lis, err := net.Listen("tcp", cfg.GRPCPort)
	if err != nil {
		log.Fatalf("❌ Не удалось открыть порт %s: %v", cfg.GRPCPort, err)
	}

	// 3. Создаем gRPC сервер
	grpcServer := grpc.NewServer()

	// 4. Регистрируем наш обработчик с мьютексом
	uiServer := scanner.NewPhoneUiServer(cfg)
	pb.RegisterPhoneUiServiceServer(grpcServer, uiServer)

	log.Printf("🚀 gRPC Сервер запущен на ноутбуке и слушает порт %s...", cfg.GRPCPort)

	// Запуск в бесконечном цикле слушать сеть
	if err := grpcServer.Serve(lis); err != nil {
		log.Fatalf("❌ Ошибка работы gRPC сервера: %v", err)
	}
}
