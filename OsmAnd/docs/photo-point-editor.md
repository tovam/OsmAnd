# Photo landmark editor

The canvas never selects an existing marker. Selection is exclusively through the numbered toolbar.

* **Explore** pans the photo/map without changing a control point.
* **Add** switches to the photo and enables repeated additions by tapping. There is no five-point cap.
* Select a number, then **Place / Move** to tap or drag that marker to its new position. A drag previews the position locally; only release saves it. The editor returns to Explore afterwards.
* Two-finger gestures zoom and rotate the photo, or zoom the satellite map. They never create a marker. Photo Plus uses full pinch strength, independent from the damped Hublot gesture.
* Delete removes the selected correspondence; Clear All asks for confirmation.
* Satellite Back returns to Photo. Results/Hublot Back returns to Satellite. Only Back from Photo closes the modal.

The editor keeps one AndroidView mounted through Photo, Satellite, Results, Hublot and Details. Hidden modes do not intercept touches. Photo framing and satellite/result framing are independent and only explicitly reframed by the Reframe action. Rotation remains canonical metadata, not an edit of the image file. Estimated purple markers belong only to the comparison view, not landmark editing.

Satellite source files remain in the existing persistent shared store. A separate 24 MiB decoded LRU survives modal/tab changes. At most three satellite loads run concurrently; changing the viewport replaces pending work, not all in-flight work. Cached parent tiles remain visible until refinement is available. Bitmap eviction never recycles a bitmap still potentially referenced by a Canvas.

Opening flight mode starts at Journals, with existing journals first and direct links to Prepare, GPX import and internal tracks. Preparation is a separate page, not a replacement for replay. Journal enumeration and preparation coverage aggregation run off Main. Photo edits do not update the hidden native flight map or recursively rescan storage after every autosave.

Host validation covers toolbar placement policy (0–999 existing markers), minimum correspondence count, back navigation, rotation/inverse geometry and the pose solver. Android touch dispatch, actual frame rate and layout still require device verification. Device checks should include: twenty additions, deleting a middle marker, moving it on each surface, alternating Photo/Satellite ten times after panning and zooming, lifting either finger during a pinch, returning from Satellite, and opening an existing journal from a fresh launch.
