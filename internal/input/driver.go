package input

//Jan Slowikowski / #include <shtrik.h>
import (
	"encoding/binary"
	"fmt"
	"os"
	"syscall"
	"time"
	"unsafe"
)

// /dev/input const
const (
	EV_KEY     = 0x01
	EV_ABS     = 0x03
	EV_SYN     = 0x0
	SYN_REPORT = 0x00
	BTN_TOUCH  = 0x14a //touch screen command
	ABS_X      = 0x00
	ABS_Y      = 0x01
	EVIOCGABS  = 0x80184540 //cord limits

)

type Driver struct {
	evDevice      *os.File
	fbData        []byte //framebuffer data
	MaxX          int32
	MaxY          int32
	ScreenW       int32
	ScreenH       int32
	BytesPerPixel int
}

type inputEvent struct {
	Time  syscall.Timeval
	Type  uint16
	Code  uint16
	Value int32
}

type absInfo struct {
	Value      int32
	Minimum    int32
	Maximum    int32
	Fuzz       int32
	Flat       int32
	Resolution int32
}

func NewDriverAutoEV(fbPath string, screenW, screenH int32, bytesPerPixel int) (*Driver, error) {
	// get Ev Path
	evPath, err := findTouchDevice()
	if err != nil {
		evPath = "/dev/input/event1"
		fmt.Println("Auto-detection failed, using default:", evPath)
	}

	fmt.Println("Using input device:", evPath)
	return NewDriver(evPath, fbPath, screenW, screenH, bytesPerPixel)
}
func findTouchDevice() (string, error) {
	for i := 0; i <= 7; i++ {
		path := fmt.Sprintf("/dev/input/event%d", i)

		// check device
		if _, err := os.Stat(path); err != nil {
			continue
		}

		// open device
		fd, err := syscall.Open(path, syscall.O_RDONLY, 0)
		if err != nil {
			continue
		}

		// check ABS_X
		var absBits [64]byte
		_, _, errno := syscall.Syscall(
			syscall.SYS_IOCTL,
			uintptr(fd),
			uintptr(0x80084568), // EVIOCGBIT(EV_ABS, ABS_MAX)
			uintptr(unsafe.Pointer(&absBits[0])),
		)
		syscall.Close(fd)

		if errno != 0 {
			continue
		}

		// check ABS_X and ABS_Y
		if (absBits[ABS_X/8]&(1<<(ABS_X%8))) != 0 &&
			(absBits[ABS_Y/8]&(1<<(ABS_Y%8))) != 0 {
			return path, nil
		}
	}

	return "", fmt.Errorf("touch device not found")
}

// if you know event number in /dev/input/...
func NewDriver(evPath, fbPath string, screenW, screenH int32, bytesPerPixel int) (*Driver, error) {
	//open input
	evFile, err := os.OpenFile(evPath, os.O_WRONLY, 0666)
	if err != nil {
		return nil, fmt.Errorf("failed to open input device %s: %w", evPath, err)

	}
	//open framebuffer
	fbFile, err := os.OpenFile(fbPath, os.O_RDONLY, 0)
	if err != nil {
		evFile.Close()
		return nil, fmt.Errorf("failed to open framebuffer device %s: %w", fbPath, err)

	}
	defer fbFile.Close()
	//memmap of framebuffer
	fbSize := int(screenW * screenH * int32(bytesPerPixel))
	//get data using mmap (pointer)
	fbData, err := syscall.Mmap(int(fbFile.Fd()), 0, fbSize, syscall.PROT_READ, syscall.MAP_SHARED)
	if err != nil {
		evFile.Close()
		return nil, fmt.Errorf("mmao failed: %w", err)
	}
	d := &Driver{
		evDevice:      evFile,
		fbData:        fbData,
		ScreenW:       screenW,
		ScreenH:       screenH,
		BytesPerPixel: bytesPerPixel,
	}
	if err := d.calibrate(); err != nil {
		evFile.Close()
		syscall.Munmap(fbData) //close framebuffer( or mem leak ahahah)
		return nil, err
	}
	return d, nil
}

// Display size calibration
func (d *Driver) calibrate() error {
	var absX, absY absInfo
	//x
	_, _, errnoX := syscall.Syscall(syscall.SYS_IOCTL, d.evDevice.Fd(), uintptr(EVIOCGABS+ABS_X), uintptr(unsafe.Pointer(&absX)))
	//y
	_, _, errnoY := syscall.Syscall(syscall.SYS_IOCTL, d.evDevice.Fd(), uintptr(EVIOCGABS+ABS_Y), uintptr(unsafe.Pointer(&absY)))
	//errCheck
	if errnoX != 0 || errnoY != 0 {
		return fmt.Errorf("failed to get abs limits via ioctl")
	}
	d.MaxX = absX.Maximum
	d.MaxY = absY.Maximum
	//if we got some problems set default values (whyyyyyyy ? )
	if d.MaxX <= 0 {
		d.MaxX = 32767
	}
	if d.MaxY <= 0 {
		d.MaxY = 32767
	}

	return nil
}

// one event for input device
func (d *Driver) write(evType, evCode uint16, value int32) error {
	nowTime := time.Now()
	ev := inputEvent{
		Time: syscall.Timeval{
			Sec:  nowTime.Unix(),
			Usec: int32(nowTime.Nanosecond() / 1000), //int64 for Linux ()
		},
		Type:  evType,
		Code:  evCode,
		Value: value,
	}
	return binary.Write(d.evDevice, binary.LittleEndian, ev)
}
func (d *Driver) GetScreenSize() (int32, int32) {
	return d.ScreenW, d.ScreenH
}
func (d *Driver) GetTouchLimits() (int32, int32) {
	return d.MaxX, d.MaxY
}
func (d *Driver) GetBytesPerPixel() int {
	return d.BytesPerPixel
}
func (d *Driver) Tap(x, y int32) error {
	rx := (x * d.MaxX) / d.ScreenW
	ry := (y * d.MaxY) / d.ScreenH

	//input
	if err := d.write(EV_ABS, ABS_X, rx); err != nil {
		return fmt.Errorf("ABS_X failed %w", err)
	}
	if err := d.write(EV_ABS, ABS_Y, ry); err != nil {
		return fmt.Errorf("ABS_Y failed %w", err)
	}

	if err := d.write(EV_KEY, BTN_TOUCH, 1); err != nil {
		return fmt.Errorf("BTN_TOUCH down failed: %w", err)
	}
	if err := d.write(EV_SYN, SYN_REPORT, 0); err != nil {
		return fmt.Errorf("SYN_REPORT down failed: %w", err)
	}

	// delay
	time.Sleep(50 * time.Millisecond)
	//untouh

	if err := d.write(EV_KEY, BTN_TOUCH, 0); err != nil {
		return fmt.Errorf("BTN_TOUCH up failed: %w", err)
	}
	if err := d.write(EV_SYN, SYN_REPORT, 0); err != nil {
		return fmt.Errorf("SYN_REPORT up failed: %w", err)
	}

	return nil
}

func (d *Driver) Swipe(x1, y1, x2, y2, durationMs int32) error {
	// set x and y for swipe and destination
	rx1 := (x1 * d.MaxX) / d.ScreenW
	ry1 := (y1 * d.MaxY) / d.ScreenH
	rx2 := (x2 * d.MaxX) / d.ScreenW
	ry2 := (y2 * d.MaxY) / d.ScreenH

	// touuch in x1, y2
	if err := d.write(EV_ABS, ABS_X, rx1); err != nil {
		return err
	}
	if err := d.write(EV_ABS, ABS_Y, ry1); err != nil {
		return err
	}
	if err := d.write(EV_KEY, BTN_TOUCH, 1); err != nil {
		return err
	}
	if err := d.write(EV_SYN, SYN_REPORT, 0); err != nil {
		return err
	}

	// Set steps for interpolation
	steps := int32(20)
	if steps < 2 {
		steps = 2
	}
	sleepPerStep := time.Duration(durationMs/steps) * time.Millisecond

	for i := int32(1); i <= steps; i++ {
		// linear interpolation
		t := float64(i) / float64(steps)
		curX := rx1 + int32(float64(rx2-rx1)*t)
		curY := ry1 + int32(float64(ry2-ry1)*t)

		if err := d.write(EV_ABS, ABS_X, curX); err != nil {
			return err
		}
		if err := d.write(EV_ABS, ABS_Y, curY); err != nil {
			return err
		}
		if err := d.write(EV_SYN, SYN_REPORT, 0); err != nil {
			return err
		}
		//delay
		time.Sleep(sleepPerStep)
	}
	//sync
	if err := d.write(EV_KEY, BTN_TOUCH, 0); err != nil {
		return err
	}
	if err := d.write(EV_SYN, SYN_REPORT, 0); err != nil {
		return err
	}

	return nil
}

func (d *Driver) Close() error {
	var err1, err2 error
	if d.evDevice != nil {
		err1 = d.evDevice.Close()
	}
	if d.fbData != nil {
		err2 = syscall.Munmap(d.fbData)
	}
	if err1 != nil {
		return err1
	}
	return err2
}
func (d *Driver) Crop(x, y, w, h int32) ([]byte, error) {
	// Проверка границ
	if x < 0 || y < 0 || w <= 0 || h <= 0 {
		return nil, fmt.Errorf("invalid crop parameters")
	}
	if x+w > d.ScreenW || y+h > d.ScreenH {
		return nil, fmt.Errorf("crop region (%d,%d,%d,%d) out of bounds %dx%d",
			x, y, w, h, d.ScreenW, d.ScreenH)
	}

	bpp := d.BytesPerPixel
	lineLen := int(d.ScreenW) * bpp
	result := make([]byte, int(w*h)*bpp)

	for row := int32(0); row < h; row++ {
		srcOffset := (int(y+row) * lineLen) + (int(x) * bpp)
		dstOffset := int(row * w * int32(bpp))

		copy(result[dstOffset:dstOffset+int(w)*bpp],
			d.fbData[srcOffset:srcOffset+int(w)*bpp])
	}

	return result, nil
}
