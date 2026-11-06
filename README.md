# UsbVision

Portrait USB-camera quiz helper for the Samsung Galaxy Tab A11 (SM-X130/X135). Put your Gemini API key in `local.properties` as `gemini.key=YOUR_KEY` (the app Gradle script injects it into `BuildConfig.GEMINI_KEY`); plug a UVC camera into the USB-C OTG port, accept the system USB permission dialog once (the `USB_DEVICE_ATTACHED` filter persists the grant across relaunches), then press volume up/down or tap the screen to grab a frame and show the answer.
