package net.osmand.plus.views.controls.maphudbuttons;

import static androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK;
import static androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK;
import static net.osmand.plus.settings.enums.CompassMode.MANUALLY_ROTATED;
import static net.osmand.plus.settings.enums.CompassMode.NORTH_IS_UP;
import static net.osmand.plus.settings.enums.CompassVisibility.ALWAYS_HIDDEN;
import static net.osmand.plus.settings.enums.CompassVisibility.ALWAYS_VISIBLE;
import static net.osmand.plus.settings.enums.CompassVisibility.VISIBLE_IF_MAP_ROTATED;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.ViewPropertyAnimatorCompat;
import androidx.core.view.ViewPropertyAnimatorListener;
import androidx.fragment.app.Fragment;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.configmap.ConfigureMapFragment;
import net.osmand.plus.render.RenderingIcons;
import net.osmand.plus.settings.controllers.CompassModeWidgetDialogController;
import net.osmand.plus.settings.enums.CompassMode;
import net.osmand.plus.settings.enums.CompassVisibility;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.views.mapwidgets.configure.buttons.CompassButtonState;
import net.osmand.plus.views.mapwidgets.configure.buttons.MapButtonState;
import net.osmand.util.MapUtils;

import org.jetbrains.annotations.NotNull;

public class CompassButton extends MapButton {

	private static final int HIDE_DELAY_MS = 5000;
	private static final float ROTATION_TEXT_SIZE_SP = 7f;

	private final CompassButtonState buttonState;
	private final Paint rotationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private ViewPropertyAnimatorCompat hideAnimator;

	private boolean forceHideCompass;
	private int displayedRotationDegrees = -1;

	public CompassButton(@NonNull Context context) {
		this(context, null);
	}

	public CompassButton(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public CompassButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		buttonState = app.getMapButtonsHelper().getCompassButtonState();
		rotationPaint.setTextAlign(Paint.Align.CENTER);
		rotationPaint.setTextSize(AndroidUtils.spToPxF(context, ROTATION_TEXT_SIZE_SP));
		rotationPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
		rotationPaint.setShadowLayer(AndroidUtils.dpToPxF(context, 1), 0, 0, Color.argb(180, 0, 0, 0));
	}

	@Nullable
	@Override
	public MapButtonState getButtonState() {
		return buttonState;
	}

	@Override
	public void setMapActivity(@NonNull @NotNull MapActivity mapActivity) {
		super.setMapActivity(mapActivity);
		setupTouchListener();
		setupAccessibilityActions();
	}

	@Override
	public void update() {
		super.update();
		float mapRotation = mapActivity.getMapRotate();
		int rotationDegrees = Math.round(MapUtils.unifyRotationTo360(mapRotation)) % 360;
		if (displayedRotationDegrees != rotationDegrees) {
			displayedRotationDegrees = rotationDegrees;
			invalidate();
		}
		if (imageView.getDrawable() instanceof CompassDrawable drawable) {
			if (drawable.getMapRotation() != mapRotation) {
				drawable.setMapRotation(mapRotation);
				imageView.invalidate();
			}
		}
		CompassMode compassMode = settings.getCompassMode();
		setContentDescription(app.getString(compassMode.getTitleId()));
	}

	@Override
	protected void updateColors(boolean nightMode) {
		rotationPaint.setColor(ColorUtilities.getMapButtonIconColor(getContext(), nightMode));
		setBackgroundColors(ColorUtilities.getMapButtonBackgroundColor(getContext(), nightMode),
				ColorUtilities.getMapButtonBackgroundPressedColor(getContext(), nightMode));
	}

	@Override
	protected void dispatchDraw(@NonNull Canvas canvas) {
		super.dispatchDraw(canvas);
		if (displayedRotationDegrees < 0) {
			return;
		}
		// Keep the baseline safely inside the circular image rather than on the
		// outer frame/shadow, including when the user customises the button size.
		float baseline = getHeight() / 2f + getImageSize() / 2f - AndroidUtils.dpToPxF(getContext(), 4);
		canvas.drawText(displayedRotationDegrees + "°", getWidth() / 2f, baseline, rotationPaint);
	}

	@Override
	protected void updateIcon() {
		String iconName = appearanceParams.getIconName();
		int iconId = AndroidUtils.getDrawableId(app, iconName);
		if (iconId == 0) {
			iconId = RenderingIcons.getBigIconResourceId(iconName);
		}
		boolean customIcon = !CompassMode.isCompassIconId(iconId);
		setIconColor(customIcon ? ColorUtilities.getMapButtonIconColor(getContext(), nightMode) : 0);

		super.updateIcon();
	}

	@SuppressLint("ClickableViewAccessibility")
	private void setupTouchListener() {
		setOnTouchListener(new View.OnTouchListener() {

			private final GestureDetector gestureDetector = new GestureDetector(getContext(), new SimpleOnGestureListener() {
				@Override
				public boolean onDoubleTap(@NonNull MotionEvent e) {
					app.getMapViewTrackingUtilities().requestSwitchCompassToNextMode();
					return true;
				}

				@Override
				public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
					Fragment fragment = mapActivity.getSupportFragmentManager().findFragmentByTag(ConfigureMapFragment.TAG);
					if (fragment != null) {
						showCompassModeWidgetDialog();
						return true;
					}
					if (settings.getCompassMode() == NORTH_IS_UP) {
						app.showShortToastMessage(R.string.compass_click_north_is_up);
					} else {
						rotateMapToNorth();
					}
					return true;
				}

				@Override
				public void onLongPress(@NonNull MotionEvent e) {
					showCompassModeWidgetDialog();
				}
			});

			@Override
			public boolean onTouch(View v, MotionEvent event) {
				return gestureDetector.onTouchEvent(event);
			}
		});
	}

	private void setupAccessibilityActions() {
		ViewCompat.replaceAccessibilityAction(this, ACTION_CLICK,
				app.getString(NORTH_IS_UP.getTitleId()), (view, arguments) -> {
					rotateMapToNorth();
					return true;
				});
		ViewCompat.replaceAccessibilityAction(this, ACTION_LONG_CLICK,
				app.getString(R.string.choose_map_orientation), (view, arguments) -> {
					showCompassModeWidgetDialog();
					return true;
				});
	}

	private void rotateMapToNorth() {
		getMapView().resetRotation();
		app.getMapViewTrackingUtilities().setLastResetRotationToNorth(System.currentTimeMillis());
		if (settings.getCompassMode() == MANUALLY_ROTATED) {
			settings.setManuallyMapRotation(0);
		}
	}

	private void showCompassModeWidgetDialog() {
		CompassModeWidgetDialogController.showDialog(mapActivity);
	}

	@Override
	protected boolean shouldShow() {
		CompassVisibility visibility = buttonState.getVisibility();
		forceHideCompass = routeDialogOpened || visibilityHelper.shouldHideCompass() || visibility == ALWAYS_HIDDEN;
		if (forceHideCompass) {
			return false;
		}
		return visibility == VISIBLE_IF_MAP_ROTATED ? mapActivity.getMapRotate() != 0 : visibility == ALWAYS_VISIBLE;
	}

	@Override
	public boolean updateVisibility(boolean visible) {
		if (visible) {
			visible = app.getAppCustomization().isFeatureEnabled(getButtonId());
		}
		if (visible != (getVisibility() == View.VISIBLE)) {
			if (visible) {
				cancelHideAnimation();
				setVisibility(VISIBLE);
				invalidate();
			} else if (hideAnimator == null) {
				if (!forceHideCompass) {
					hideDelayed(HIDE_DELAY_MS);
				} else {
					forceHideCompass = false;
					setVisibility(GONE);
					invalidate();
				}
			}
			return true;
		} else if (visible && hideAnimator != null) {
			cancelHideAnimation();
			setVisibility(VISIBLE);
			invalidate();
			return true;
		}
		return false;
	}

	public void hideDelayed(long msec) {
		if (getVisibility() == VISIBLE) {
			cancelHideAnimation();
			hideAnimator = ViewCompat.animate(this)
					.alpha(0f)
					.setDuration(250)
					.setStartDelay(msec)
					.setListener(new ViewPropertyAnimatorListener() {
						@Override
						public void onAnimationStart(@NotNull View view) {
						}

						@Override
						public void onAnimationEnd(@NotNull View view) {
							view.setVisibility(View.GONE);
							view.setAlpha(1f);
							hideAnimator = null;
						}

						@Override
						public void onAnimationCancel(@NotNull View view) {
							view.setVisibility(View.GONE);
							view.setAlpha(1f);
							hideAnimator = null;
						}
					});
			hideAnimator.start();
		}
	}

	public void cancelHideAnimation() {
		if (hideAnimator != null) {
			hideAnimator.cancel();
		}
	}
}
