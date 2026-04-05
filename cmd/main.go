package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	// ВНИМАНИЕ: Укажи правильный путь к сгенерированным файлам
	// Он должен совпадать с вашим go.mod + путем к папке
	pb "github.com/Vancheszz/android-agent/internal/proto"
)

func main() {
	// 1. Подключаемся к эмулятору (через проброшенный порт)
	addr := "localhost:9999"
	conn, err := grpc.Dial(addr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		log.Fatalf("Не удалось подключиться: %v", err)
	}
	defer conn.Close()

	client := pb.NewRatatoskrClient(conn)

	// Контекст с таймаутом (важно для микросервисов)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	fmt.Println("🛰 Отправляю запрос GetScreenState...")

	// 2. Запрашиваем состояние экрана и скриншот
	req := &pb.ScreenRequest{
		IncludeScreenshot:  true,
		CompressionQuality: 0.5,
		IncludeVisibleOnly: true,
	}

	res, err := client.GetScreenState(ctx, req)
	if err != nil {
		log.Fatalf("Ошибка вызова gRPC: %v", err)
	}

	fmt.Printf("✅ Ответ получен! Пакет: %s, Нод: %d\n", res.PackageName, len(res.Nodes))

	// 3. Сохраняем скриншот, чтобы убедиться, что всё работает
	if len(res.Screenshot) > 0 {
		filename := "screenshot_debug.jpg"
		err := os.WriteFile(filename, res.Screenshot, 0644)
		if err != nil {
			log.Printf("Не удалось сохранить скриншот: %v", err)
		} else {
			fmt.Printf("🖼 Скриншот сохранен в файл: %s\n", filename)
		}
	}

	// 4. Тестовое действие: Клик в центр экрана (540, 960 для 1080p)
	fmt.Println("🖱 Пробую сделать клик в центр экрана...")
	actionRes, err := client.PerformAction(ctx, &pb.ActionRequest{
		Type: pb.ActionRequest_CLICK,
		X:    540,
		Y:    960,
	})

	if err != nil {
		log.Printf("Ошибка при клике: %v", err)
	} else {
		fmt.Printf("🚀 Клик выполнен! Статус: %v\n", actionRes.Success)
	}
}
