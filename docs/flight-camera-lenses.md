# Physical camera selection

The previous selector enumerated logical CameraX cameras, not every physical lens. Logical cameras can switch lenses during zoom.

The new selector discovers physical camera IDs and uses CameraX 1.4.2 `Camera2Interop.Extender.setPhysicalCameraId` for both Preview and ImageCapture. Single-camera devices remain supported. A logical camera without accessible physical IDs is explicitly labelled non-lockable. Failed physical binding never falls back silently to automatic selection.

Labels compare focal length divided by sensor width. The main lens is estimated as the module closest to a conventional 24 mm equivalent field of view. No Pixel camera IDs are hardcoded. The rear main fixed lens is selected initially.

The UI separates physical lens selection, zoom, and Exposure / Sharpness / Colour settings. Auto/Manual and white-balance choices have exclusive radio semantics. The bounded scrolling settings panel does not displace the shutter. Autofocus reports success/failure; tapping does not override manual focus and pinching does not trigger tap autofocus.

## Required Pixel 9 Pro verification

1. Confirm ultra-wide, main, telephoto and selfie entries appear. Accessibility depends on Android's camera provider.
2. Select each lens and pinch through its zoom range. Verify no lens switch, and compare saved-photo framing with preview.
3. Test at 40 cm and on a distant subject. Compare autofocus results and the advertised minimum focus distance; the reported symptom alone does not prove a hardware limit.
4. Switch Auto/Manual focus and tap. Manual must remain manual. Pinch and release without triggering autofocus.
5. Switch lenses and take photos repeatedly. A failed binding must show an error, never capture using another lens.
6. Check portrait, landscape and enlarged fonts. Only the selected settings group should be visible.

The isolated optics tests verify ratio calculations, invalid metadata, enumeration-order independence and default selection. They do not validate a Pixel driver or replace device testing.

References: [Android physical stream selection](https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2Interop.Extender), [Android multi-camera](https://developer.android.com/media/camera/camera2/multi-camera).
