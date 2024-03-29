

package com.example.fispro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PreviewCallback;
import android.net.Uri;
import android.os.Bundle;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View.OnLongClickListener;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Main activity for IDEAL Magnifier. Uses the phone's camera to turn Android
 * into a video magnifier. Volume buttons to zoom in/out Search button to turn
 * on the LED light Menu to bring up the color filter options If the image is
 * blurry, just tap the screen and it will refocus.
 */
public class MagnifierActivity extends Activity implements Callback {

    /**
     * The user interaction cool down period in milliseconds. The camera's
     * autoFocus method is invoked after this delay if no further user
     * interactions occur.
     */
    public static final long FOCUS_INTERACTION_TIMEOUT_THRESHOLD = 750;

    /**
     * Runnable used to defer camera focusing until user interaction has stopped
     * for FOCUS_INTERACTION_TIMEOUT_THRESHOLD milliseconds.
     */
    private Runnable mFocuserLocked;

    /**
     * A flag indicating whether the camera is currently performing an
     * auto-focus operation.
     */
    private boolean mCameraFocusing = false;

    /**
     * A flag indicating whether or not a user interaction occurred during the
     * last camera focus event. Used to defer updating of camera parameters
     * until the focus operation completes.
     */
    private boolean mParameterSettingDeferred = false;

    /**
     * The set of camera parameters to be applied after a focus operation
     * completes.
     */
    private Parameters mDeferredParameters = null;

    /**
     * The Layout Manager holding either the MagnificationView or
     * MagnifiedImageView
     */
    private LinearLayout mRootView = null;

    /**
     * Surface used to project the camera preview.
     */
    private SurfaceHolder mHolder = null;

    /**
     * Abstracted SurfaceView used to further magnify the camera preview frames.
     */
    private MagnificationView mPreview = null;

    /**
     * Abstracted ImageView used to further magnify a single camera preview
     * frame.
     */
    private MagnifiedImageView mImagePreview = null;

    /**
     * The system's camera hardware
     */
    private Camera mCamera = null;

    /**
     * Handler for long click events
     */
    private OnLongClickListener mLongClickListener = null;
    
    /**
     * Callback used to obtain camera data for pausing the magnifier.
     */
    private PreviewCallback mPreviewCallback = null;

    /**
     * The current zoom level
     */
    private int mZoom = 0;

    /**
     * The current camera mode
     */
    private String mCameraMode = null;

    /**
     * Flag indicating the current state of the camera flash LED.
     */
    private boolean mTorch = false;

    /**
     * Flag indicating the state of the surface, true indicating paused.
     */
    private boolean mMagnifierPaused = false;

    /**
     * The Preferences in which we store the last application state.
     */
    private SharedPreferences mPrefs = null;

    /**
     * These classes lie about our actual screen size, thus giving us a 2x
     * digital zoom to start with even before we invoke the hardware zoom
     * features of the camera.
     */
    public class MagnificationView extends SurfaceView {
        public MagnificationView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // TODO: This method miscalculates the dimensions of the projected
            // screen, causing a horizontal stretch effect.
            Display display = getWindowManager().getDefaultDisplay();
            int width = display.getWidth();
            int height = display.getHeight();
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(width * 2, MeasureSpec.EXACTLY);
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(height * 2, MeasureSpec.EXACTLY);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    public class MagnifiedImageView extends ImageView {
        public MagnifiedImageView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // TODO: This method miscalculates the dimensions of the projected
            // screen, causing a horizontal stretch effect.
            Display display = getWindowManager().getDefaultDisplay();
            int width = display.getWidth();
            int height = display.getHeight();
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(width * 2, MeasureSpec.EXACTLY);
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(height * 2, MeasureSpec.EXACTLY);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);
        mPreview = new MagnificationView(this);
        mImagePreview = new MagnifiedImageView(this);
        mLongClickListener = new OnLongClickListener() {
            // When any view is long clicked, toggle pausing the magnifier.
            @Override
            public boolean onLongClick(View v) {
                togglePausePreview();
                return true;
            }
        };
        mPreview.setOnLongClickListener(mLongClickListener);
        mImagePreview.setOnLongClickListener(mLongClickListener);
        mPreviewCallback = new PreviewCallback() {
            @Override
            /**
             * This callback will be used to capture a single frame from the camera
             * hardware.  We process the image in YUV format, convert to JPEG, and
             * finally to Bitmap so it can be shown in an extended ImageView.  This
             * process is sub-optimal as Android's graphics BitmapFactory does not
             * support direct YUV to Bitmap conversions.
             */
            public void onPreviewFrame(byte[] data, Camera camera) {
                Camera.Parameters parameters = camera.getParameters();

                // Generate a YuvImage from the camera data
                int w = parameters.getPreviewSize().width;
                int h = parameters.getPreviewSize().height;
                YuvImage pausedYuvImage = new YuvImage(data, parameters.getPreviewFormat(), w, h,
                        null);

                // Compress the YuvImage to JPEG Output Stream
                ByteArrayOutputStream jpegOutputStream = new ByteArrayOutputStream();
                pausedYuvImage.compressToJpeg(new Rect(0, 0, w, h), 100, jpegOutputStream);

                // Use BitmapFactory to create a Bitmap from the JPEG stream
                Bitmap pausedImage = BitmapFactory.decodeByteArray(jpegOutputStream.toByteArray(),
                        0, jpegOutputStream.size());

                // Scale the Bitmap to match the MagnifiedImageView's dimensions
                Display display = getWindowManager().getDefaultDisplay();
                int width = display.getWidth();
                int height = display.getHeight();
                mImagePreview.setImageBitmap(Bitmap.createScaledBitmap(pausedImage, width * 2,
                        height * 2, false));
                mRootView.removeAllViews();
                mRootView.addView(mImagePreview);
            }
        };
        mRootView = (LinearLayout) findViewById(R.id.rootView);
        mRootView.addView(mPreview);
        mPreview.setKeepScreenOn(true);
        mHolder = mPreview.getHolder();
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mFocuserLocked = new Runnable() {
            @Override
            public void run() {
                // This Runnable will focus the camera, and guarantee that calls
                // to Camera.autoFocus are synchronous.
                if (mCamera != null && !mCameraFocusing) {
                    mCameraFocusing = true;
                    mCamera.autoFocus(new AutoFocusCallback() {
                        @Override
                        public void onAutoFocus(boolean success, Camera camera) {
                            mCameraFocusing = false;
                            if (mParameterSettingDeferred) {
                                // The user attempted to change a camera
                                // parameter during the focus event, set the
                                // parameters again so the user's change
                                // takes effect.
                                setParams(mDeferredParameters);
                                mParameterSettingDeferred = false;
                            }
                        }
                    });
                }
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mCamera != null) {
            Parameters params = mCamera.getParameters();
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                mZoom = mZoom + 1;
                if (mZoom > params.getMaxZoom()) {
                    mZoom = params.getMaxZoom();
                }
                params.setZoom(mZoom);
                setParams(params);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                mZoom = mZoom - 1;
                if (mZoom < 0) {
                    mZoom = 0;
                }
                params.setZoom(mZoom);
                setParams(params);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_SEARCH) {
                if (mTorch) {
                    params.setFlashMode(Parameters.FLASH_MODE_OFF);
                    mTorch = false;
                } else {
                    params.setFlashMode(Parameters.FLASH_MODE_TORCH);
                    mTorch = true;
                }
                setParams(params);
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || (keyCode == KeyEvent.KEYCODE_BACK && mMagnifierPaused)) {
            togglePausePreview();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mFocuserLocked.run();
        return super.onTouchEvent(event);
    }

    private void setParams(Parameters params) {
        // Setting the parameters of the camera is only a safe operation if the
        // camera is not presently focusing. Only focus the camera after the
        // user interaction has quieted down, verified by a delay period.
        mPreview.removeCallbacks(mFocuserLocked);
        if (!mCameraFocusing) {
            // On some phones such as the Motorola Droid, the preview needs to
            // be stopped and then restarted.
            // Failing to do so will result in an ANR (application not
            // responding) error.
            //
            // TODO: Check if it is possible to detect when a restart is needed
            // by checking isSmoothZoomSupported in the Camera Parameters.
            //
            // Nexus One: False (does not need a restart)
            // Motorola Droid: True (must have preview restarted)
            //
            // Log.e("smooth zoom?", params.isSmoothZoomSupported() + "");
            mCamera.stopPreview();
            mCamera.startPreview();
            mCamera.setParameters(params);
        } else {
            mParameterSettingDeferred = true;
            mDeferredParameters = params;
        }
        mPreview.postDelayed(mFocuserLocked, FOCUS_INTERACTION_TIMEOUT_THRESHOLD);
    }

    public void surfaceCreated(SurfaceHolder holder) {
        mCamera = Camera.open();
        try {
            mCamera.setPreviewDisplay(holder);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Parameters initParams = mCamera.getParameters();
        initParams.setPreviewFormat(ImageFormat.NV21);
        setParams(initParams);
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        mCamera.stopPreview();
        mCamera.release();
        mCamera = null;
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        mHolder.setFixedSize(w, h);

        mCamera.startPreview();

        // Note: Parameters are not safe to set until AFTER the preview is up.
        // One some phones, it is OK (such as the Nexus One), but on others,
        // (such as the Motorola Droid), this will cause the parameters to not
        // actually change/may lead to an ANR on a subsequent set attempt.
        Parameters params = mCamera.getParameters();

        // Reload the previous state from the stored preferences.
        mPrefs = getSharedPreferences(getString(R.string.app_name), 0);
        mZoom = mPrefs.getInt(getString(R.string.zoom_level_pref), mCamera.getParameters()
                .getMaxZoom() / 2);
        mCameraMode = mPrefs.getString(getString(R.string.camera_mode_pref), mCamera
                .getParameters().getSupportedColorEffects().get(0));
        params.setZoom(mZoom);
        params.setColorEffect(mCameraMode);
        setParams(params);
    }

    public void showEffectsList() {
        if (mCamera == null) {
            return;
        }

        List<String> effectsList = mCamera.getParameters().getSupportedColorEffects();
        String[] effects = {
            ""
        };
        effects = effectsList.toArray(effects);
        final String[] items = effects;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.color_effect_dialog_title));
        builder.setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int item) {
                Parameters params = mCamera.getParameters();
                mCameraMode = items[item];
                params.setColorEffect(items[item]);
                setParams(params);
            }
        });
        builder.create().show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        // Parameters for menu.add are:
        // group -- Not used here.
        // id -- Used only when you want to handle and identify the click
        // yourself.
        // title
        menu.add(0, 0, 0, getString(R.string.color_effect_button_text)).setIcon(
                android.R.drawable.ic_menu_manage);
        menu.add(0, 1, 0, getString(R.string.toggle_freeze_frame_button_text)).setIcon(
                android.R.drawable.ic_menu_camera);
        menu.add(0, 2, 0, getString(R.string.more_apps_button_text)).setIcon(
                android.R.drawable.ic_menu_search);
        return true;
    }

    /**
     * Activity callback that lets your handle the selection in the class.
     * Return true to indicate that you've got it, false to indicate that it
     * should be handled by a declared handler object for that item (handler
     * objects are discouraged for reasons of efficiency).
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 0:
                showEffectsList();
                return true;
            case 1:
                togglePausePreview();
                return true;
            case 2:
                String marketUrl = "market://search?q=pub:\"IDEAL Group, Inc. Android Development Team\"";
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(marketUrl));
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException anf) {
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.market_launch_error_title))
                            .setMessage(getString(R.string.market_launch_error_text))
                            .setNeutralButton(getString(R.string.market_launch_error_button), null)
                            .show();
                }
                return true;
        }
        return false;
    }

    /**
     * Toggles the freeze frame feature by removing the MagnificationView and
     * adding a MagnifiedImageView with a single scaled preview frame.
     */
    public void togglePausePreview() {
        mMagnifierPaused = !mMagnifierPaused;
        if (!mMagnifierPaused) {
            mRootView.removeAllViews();
            mRootView.addView(mPreview);
        } else {
            mCamera.setOneShotPreviewCallback(mPreviewCallback);
        }
    }

    /**
     * Save the state of the application as it loses focus.
     */
    @Override
    public void onPause() {
        super.onPause();

        SharedPreferences.Editor editor = mPrefs.edit();
        editor.putInt(getString(R.string.zoom_level_pref), mZoom);
        editor.putString(getString(R.string.camera_mode_pref), mCameraMode);
        editor.commit();
    }
}
