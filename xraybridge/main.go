package main

/*
#include <stdlib.h>
int neotun_protect_fd(int fd);
*/
import "C"

import (
    "errors"
    "os"
    "path/filepath"
    "unsafe"

    libXray "github.com/xtls/libxray"
)

type androidController struct{}

func (androidController) ProtectFd(fd int) bool {
    return C.neotun_protect_fd(C.int(fd)) != 0
}

func main() {}

//export NeotunSetAssetPath
func NeotunSetAssetPath(path *C.char) *C.char {
    if path == nil || C.GoString(path) == "" {
        return C.CString("Xray asset path is empty")
    }
    assetPath := filepath.Clean(C.GoString(path))
    if err := os.Setenv("XRAY_LOCATION_ASSET", assetPath); err != nil {
        return C.CString(err.Error())
    }
    if err := os.Setenv("xray.location.asset", assetPath); err != nil {
        return C.CString(err.Error())
    }
    return nil
}

//export NeotunPrepare
func NeotunPrepare(server *C.char) *C.char {
    controller := androidController{}
    libXray.RegisterDialerController(controller)
    libXray.RegisterListenerController(controller)

    if server != nil {
        if err := libXray.SetDNS(controller, C.GoString(server)); err != nil {
            return C.CString(err.Error())
        }
    }
    return nil
}

//export NeotunInvoke
func NeotunInvoke(request *C.char) *C.char {
    if request == nil {
        return C.CString(errors.New("Xray request is null").Error())
    }
    return C.CString(libXray.Invoke(C.GoString(request)))
}

//export NeotunResetDNS
func NeotunResetDNS() {
    libXray.ResetDNS()
}

//export NeotunFree
func NeotunFree(value *C.char) {
    if value != nil {
        C.free(unsafe.Pointer(value))
    }
}
