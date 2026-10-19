//go:build tools

// This file exists solely to keep golang.org/x/mobile in go.mod.
//
// gomobile requires the module to depend on x/mobile, but nothing in bridge.go imports it, so
// `go mod tidy` prunes it and the next `gomobile bind` fails with "missing golang.org/x/mobile
// dependency". The `tools` build tag keeps this out of the actual build.
package tools

import _ "golang.org/x/mobile/bind"
