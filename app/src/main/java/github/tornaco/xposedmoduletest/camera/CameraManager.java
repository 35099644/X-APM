/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package github.tornaco.xposedmoduletest.camera;

import android.Manifest;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import github.tornaco.xposedmoduletest.bean.AccessInfo;
import github.tornaco.xposedmoduletest.bean.DaoManager;
import github.tornaco.xposedmoduletest.bean.DaoSession;
import github.tornaco.xposedmoduletest.x.XApp;
import github.tornaco.xposedmoduletest.x.XExecutor;
import github.tornaco.xposedmoduletest.x.XSettings;

/**
 * Class which manages interactions with the camera, but does not do any UI.  This class is
 * designed to be a singleton to ensure there is one component managing the camera and releasing
 * the native resources.
 * In order to acquire a camera, a caller must:
 * <ul>
 * <li>Call selectCamera to select front or back camera
 * <li>Call setSurface to control where the preview is shown
 * <li>Call openCamera to request the camera start preview
 * </ul>
 * Callers should call onPause and onResume to ensure that the camera is release while the activity
 * is not active.
 * This class is not thread safe.  It should only be called from one thread (the UI thread or test
 * thread)
 */
public class CameraManager implements FocusOverlayManager.Listener {
    // Error codes
    static final int ERROR_OPENING_CAMERA = 1;
    static final int ERROR_SHOWING_PREVIEW = 2;
    static final int ERROR_INITIALIZING_VIDEO = 3;
    static final int ERROR_STORAGE_FAILURE = 4;
    static final int ERROR_RECORDING_VIDEO = 5;
    static final int ERROR_HARDWARE_ACCELERATION_DISABLED = 6;
    static final int ERROR_TAKING_PICTURE = 7;
    private static final String TAG = CameraManager.class.getSimpleName();
    private static final int NO_CAMERA_SELECTED = -1;
    private static CameraManager sInstance;
    /**
     * Default camera wrapper which directs calls to the framework APIs
     */
    private static CameraWrapper sCameraWrapper = new CameraWrapper() {
        @Override
        public int getNumberOfCameras() {
            return Camera.getNumberOfCameras();
        }

        @Override
        public void getCameraInfo(final int index, final CameraInfo cameraInfo) {
            Camera.getCameraInfo(index, cameraInfo);
        }

        @Override
        public Camera open(final int cameraId) {
            return Camera.open(cameraId);
        }

        @Override
        public void release(final Camera camera) {
            camera.release();
        }
    };
    /**
     * The CameraInfo for the currently selected camera
     */
    private final CameraInfo mCameraInfo;
    /**
     * True if the device has front and back cameras
     */
    private final boolean mHasFrontAndBackCamera;
    /**
     * Manages auto focus visual and behavior
     */
    private final FocusOverlayManager mFocusOverlayManager;
    /**
     * The index of the selected camera or NO_CAMERA_SELECTED if a camera hasn'data been selected yet
     */
    private int mCameraIndex;
    /**
     * True if the camera should be open (may not yet be actually open)
     */
    private boolean mOpenRequested;
    /**
     * True if the camera is requested to be in video mode
     */
    private boolean mVideoModeRequested;
    /**
     * The media recorder for video mode
     */
    private MmsVideoRecorder mMediaRecorder;
    /**
     * Callback to call with video recording updates
     */
    private MediaCallback mVideoCallback;
    /**
     * The preview view to show the preview on
     */
    private CameraPreview mCameraPreview;
    /**
     * The helper classs to handle orientation changes
     */
    private OrientationHandler mOrientationHandler;
    /**
     * Tracks whether the preview has hardware acceleration
     */
    private boolean mIsHardwareAccelerationSupported;
    /**
     * The task for opening the camera, so it doesn'data block the UI thread
     * Using AsyncTask rather than SafeAsyncTask because the tasks need to be serialized, but don'data
     * need to be on the UI thread
     * TODO: If we have other AyncTasks (not SafeAsyncTasks) this may contend and we may
     * need to create a dedicated thread, or synchronize the threads in the thread pool
     */
    private AsyncTask<Integer, Void, Camera> mOpenCameraTask;
    /**
     * The camera index that is queued to be opened, but not completed yet, or NO_CAMERA_SELECTED if
     * no open task is pending
     */
    private int mPendingOpenCameraIndex = NO_CAMERA_SELECTED;
    /**
     * The instance of the currently opened camera
     */
    private Camera mCamera;
    /**
     * The rotation of the screen relative to the camera's natural orientation
     */
    private int mRotation;
    /**
     * The callback to notify when errors or other events occur
     */
    private CameraManagerListener mListener;
    /**
     * True if the camera is currently in the process of taking an image
     */
    private boolean mTakingPicture;

    private Integer mSavedOrientation = null;

    private CameraManager() {
        mCameraInfo = new CameraInfo();
        mCameraIndex = NO_CAMERA_SELECTED;

        // Check to see if a front and back camera exist
        boolean hasFrontCamera = false;
        boolean hasBackCamera = false;
        final CameraInfo cameraInfo = new CameraInfo();
        final int cameraCount = sCameraWrapper.getNumberOfCameras();
        try {
            for (int i = 0; i < cameraCount; i++) {
                sCameraWrapper.getCameraInfo(i, cameraInfo);
                if (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
                    hasFrontCamera = true;
                } else if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
                    hasBackCamera = true;
                }
                if (hasFrontCamera && hasBackCamera) {
                    break;
                }
            }
        } catch (final RuntimeException e) {
            Log.e(TAG, "Unable to load camera info", e);
        }
        mHasFrontAndBackCamera = hasFrontCamera && hasBackCamera;
        mFocusOverlayManager = new FocusOverlayManager(this, Looper.getMainLooper());

        // Assume the best until we are proven otherwise
        mIsHardwareAccelerationSupported = true;
    }

    /**
     * Gets the singleton instance
     */
    public static CameraManager get() {
        if (sInstance == null) {
            sInstance = new CameraManager();
        }
        return sInstance;
    }

    /**
     * Allows tests to inject a custom camera wrapper
     */
    static void setCameraWrapper(final CameraWrapper cameraWrapper) {
        sCameraWrapper = cameraWrapper;
        sInstance = null;
    }

    static boolean hasCameraPermission() {
        return OsUtil.hasPermission(Manifest.permission.CAMERA);
    }

    /**
     * Sets the surface to use to display the preview
     * This must only be called AFTER the CameraPreview has a texture ready
     *
     * @param preview The preview surface view
     */
    void setSurface(final CameraPreview preview) {
        if (preview == mCameraPreview) {
            return;
        }

        if (preview != null) {
            preview.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(final View view, final MotionEvent motionEvent) {
                    if ((motionEvent.getActionMasked() & MotionEvent.ACTION_UP) ==
                            MotionEvent.ACTION_UP) {
                        mFocusOverlayManager.setPreviewSize(view.getWidth(), view.getHeight());
                        mFocusOverlayManager.onSingleTapUp(
                                (int) motionEvent.getX() + view.getLeft(),
                                (int) motionEvent.getY() + view.getTop());
                    }
                    return true;
                }
            });
        }
        mCameraPreview = preview;
        tryShowPreview();
    }

    void setRenderOverlay(final RenderOverlay renderOverlay) {
        mFocusOverlayManager.setFocusRenderer(renderOverlay != null ?
                renderOverlay.getPieRenderer() : null);
    }

    /**
     * Convenience function to swap between front and back facing cameras
     */
    public void swapCamera() {
        if (mCameraIndex >= 0) {
            selectCamera(mCameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT ?
                    CameraInfo.CAMERA_FACING_BACK :
                    CameraInfo.CAMERA_FACING_FRONT);
        }
    }

    /**
     * Selects the first camera facing the desired direction, or the first camera if there is no
     * camera in the desired direction
     *
     * @param desiredFacing One of the CameraInfo.CAMERA_FACING_* constants
     * @return True if a camera was selected, or false if selecting a camera failed
     */
    boolean selectCamera(final int desiredFacing) {
        try {
            // We already selected a camera facing that direction
            if (mCameraIndex >= 0 && mCameraInfo.facing == desiredFacing) {
                return true;
            }

            final int cameraCount = sCameraWrapper.getNumberOfCameras();

            mCameraIndex = NO_CAMERA_SELECTED;
            setCamera(null);
            final CameraInfo cameraInfo = new CameraInfo();
            for (int i = 0; i < cameraCount; i++) {
                sCameraWrapper.getCameraInfo(i, cameraInfo);
                if (cameraInfo.facing == desiredFacing) {
                    mCameraIndex = i;
                    sCameraWrapper.getCameraInfo(i, mCameraInfo);
                    break;
                }
            }

            // There's no camera in the desired facing direction, just select the first camera
            // regardless of direction
            if (mCameraIndex < 0) {
                mCameraIndex = 0;
                sCameraWrapper.getCameraInfo(0, mCameraInfo);
            }

            if (mOpenRequested) {
                // The camera is open, so reopen with the newly selected camera
                openCamera();
            }
            return true;
        } catch (final RuntimeException e) {
            Log.e(TAG, "RuntimeException in CameraManager.selectCamera", e);
            if (mListener != null) {
                mListener.onCameraError(ERROR_OPENING_CAMERA, e);
            }
            return false;
        }
    }

    int getCameraIndex() {
        return mCameraIndex;
    }

    void selectCameraByIndex(final int cameraIndex) {
        if (mCameraIndex == cameraIndex) {
            return;
        }

        try {
            mCameraIndex = cameraIndex;
            sCameraWrapper.getCameraInfo(mCameraIndex, mCameraInfo);
            if (mOpenRequested) {
                openCamera();
            }
        } catch (final RuntimeException e) {
            Log.e(TAG, "RuntimeException in CameraManager.selectCameraByIndex", e);
            if (mListener != null) {
                mListener.onCameraError(ERROR_OPENING_CAMERA, e);
            }
        }
    }

    CameraInfo getCameraInfo() {
        if (mCameraIndex == NO_CAMERA_SELECTED) {
            return null;
        }
        return mCameraInfo;
    }

    /**
     * @return True if this device has camera capabilities
     */
    boolean hasAnyCamera() {
        return sCameraWrapper.getNumberOfCameras() > 0;
    }

    /**
     * @return True if the device has both a front and back camera
     */
    boolean hasFrontAndBackCamera() {
        return mHasFrontAndBackCamera;
    }

    /**
     * Opens the camera on a separate thread and initiates the preview if one is available
     */
    void openCamera() {
        if (mCameraIndex == NO_CAMERA_SELECTED) {
            // Ensure a selected camera if none is currently selected. This may happen if the
            // camera chooser is not the default media chooser.
            selectCamera(Camera.CameraInfo.CAMERA_FACING_FRONT);
        }
        mOpenRequested = true;
        // We're already opening the camera or already have the camera handle, nothing more to do
        if (mPendingOpenCameraIndex == mCameraIndex || mCamera != null) {
            return;
        }

        // True if the task to open the camera has to be delayed until the current one completes
        boolean delayTask = false;

        // Cancel any previous open camera tasks
        if (mOpenCameraTask != null) {
            mPendingOpenCameraIndex = NO_CAMERA_SELECTED;
            delayTask = true;
        }

        mPendingOpenCameraIndex = mCameraIndex;
        mOpenCameraTask = new AsyncTask<Integer, Void, Camera>() {
            private Exception mException;

            @Override
            protected Camera doInBackground(final Integer... params) {
                try {
                    final int cameraIndex = params[0];
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Opening camera " + mCameraIndex);
                    }
                    return sCameraWrapper.open(cameraIndex);
                } catch (final Exception e) {
                    Log.e(TAG, "Exception while opening camera", e);
                    mException = e;
                    return null;
                }
            }

            @Override
            protected void onPostExecute(final Camera camera) {
                // If we completed, but no longer want this camera, then release the camera
                if (mOpenCameraTask != this || !mOpenRequested) {
                    releaseCamera(camera);
                    cleanup();
                    return;
                }

                cleanup();

                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Opened camera " + mCameraIndex + " " + (camera != null));
                }

                setCamera(camera);
                if (camera == null) {
                    if (mListener != null) {
                        mListener.onCameraError(ERROR_OPENING_CAMERA, mException);
                    }
                    Log.e(TAG, "Error opening camera");
                }
            }

            @Override
            protected void onCancelled() {
                super.onCancelled();
                cleanup();
            }

            private void cleanup() {
                mPendingOpenCameraIndex = NO_CAMERA_SELECTED;
                if (mOpenCameraTask != null && mOpenCameraTask.getStatus() == Status.PENDING) {
                    // If there's another task waiting on this one to complete, start it now
                    mOpenCameraTask.execute(mCameraIndex);
                } else {
                    mOpenCameraTask = null;
                }

            }
        };
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Start opening camera " + mCameraIndex);
        }

        if (!delayTask) {
            mOpenCameraTask.execute(mCameraIndex);
        }
    }

    boolean isVideoMode() {
        return mVideoModeRequested;
    }

    void setVideoMode(final boolean videoMode) {
        if (mVideoModeRequested == videoMode) {
            return;
        }
        mVideoModeRequested = videoMode;
        tryInitOrCleanupVideoMode();
    }

    boolean isRecording() {
        return mVideoModeRequested && mVideoCallback != null;
    }

    /**
     * Closes the camera releasing the resources it uses
     */
    void closeCamera() {
        mOpenRequested = false;
        setCamera(null);
    }

    /**
     * Temporarily closes the camera if it is open
     */
    void onPause() {
        setCamera(null);
    }

    /**
     * Reopens the camera if it was opened when onPause was called
     */
    void onResume() {
        if (mOpenRequested) {
            openCamera();
        }
    }

    /**
     * Sets the listener which will be notified of errors or other events in the camera
     *
     * @param listener The listener to notify
     */
    void setListener(final CameraManagerListener listener) {
        mListener = listener;
        if (!mIsHardwareAccelerationSupported && mListener != null) {
            mListener.onCameraError(ERROR_HARDWARE_ACCELERATION_DISABLED, null);
        }
    }

    void startVideo(final MediaCallback callback) {
        mVideoCallback = callback;
        tryStartVideoCapture();
    }

    /**
     * Asynchronously releases a camera
     *
     * @param camera The camera to release
     */
    private void releaseCamera(final Camera camera) {
        if (camera == null) {
            return;
        }

        mFocusOverlayManager.onCameraReleased();

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(final Void... params) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Releasing camera " + mCameraIndex);
                }
                sCameraWrapper.release(camera);
                return null;
            }
        }.execute();
    }

    private void releaseMediaRecorder(final boolean cleanupFile) {
        if (mMediaRecorder == null) {
            return;
        }
        mVideoModeRequested = false;

        if (cleanupFile) {
            mMediaRecorder.cleanupTempFile();
            if (mVideoCallback != null) {
                final MediaCallback callback = mVideoCallback;
                mVideoCallback = null;
                // Notify the callback that we've stopped recording
                callback.onMediaReady(null /*uri*/, null /*contentType*/, 0 /*width*/,
                        0 /*height*/);
            }
        }

        mMediaRecorder.release();
        mMediaRecorder = null;

        if (mCamera != null) {
            try {
                mCamera.reconnect();
            } catch (final IOException e) {
                Log.e(TAG, "IOException in CameraManager.releaseMediaRecorder", e);
                if (mListener != null) {
                    mListener.onCameraError(ERROR_OPENING_CAMERA, e);
                }
            } catch (final RuntimeException e) {
                Log.e(TAG, "RuntimeException in CameraManager.releaseMediaRecorder", e);
                if (mListener != null) {
                    mListener.onCameraError(ERROR_OPENING_CAMERA, e);
                }
            }
        }
    }

    /**
     * Updates the orientation of the camera to match the orientation of the device
     */
    private void updateCameraOrientation() {
        if (mCamera == null || mCameraPreview == null || mTakingPicture) {
            return;
        }

        final WindowManager windowManager =
                (WindowManager) mCameraPreview.getContext().getSystemService(
                        Context.WINDOW_SERVICE);

        int degrees = 0;
        switch (windowManager.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        // The display orientation of the camera (this controls the preview image).
        int orientation;

        // The clockwise rotation angle relative to the orientation of the camera. This affects
        // pictures returned by the camera in Camera.PictureCallback.
        int rotation;
        if (mCameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
            orientation = (mCameraInfo.orientation + degrees) % 360;
            rotation = orientation;
            // compensate the mirror but only for orientation
            orientation = (360 - orientation) % 360;
        } else {  // back-facing
            orientation = (mCameraInfo.orientation - degrees + 360) % 360;
            rotation = orientation;
        }
        mRotation = rotation;
        if (mMediaRecorder == null) {
            try {
                mCamera.setDisplayOrientation(orientation);
                final Camera.Parameters params = mCamera.getParameters();
                params.setRotation(rotation);
                mCamera.setParameters(params);
            } catch (final RuntimeException e) {
                Log.e(TAG, "RuntimeException in CameraManager.updateCameraOrientation", e);
                if (mListener != null) {
                    mListener.onCameraError(ERROR_OPENING_CAMERA, e);
                }
            }
        }
    }

    /**
     * Sets the current camera, releasing any previously opened camera
     */
    private void setCamera(final Camera camera) {
        if (mCamera == camera) {
            return;
        }

        releaseMediaRecorder(true /* cleanupFile */);
        releaseCamera(mCamera);
        mCamera = camera;
        tryShowPreview();
        if (mListener != null) {
            mListener.onCameraChanged();
        }
    }

    /**
     * Shows the preview if the camera is open and the preview is loaded
     */
    private void tryShowPreview() {
        if (mCameraPreview == null || mCamera == null) {
            if (mOrientationHandler != null) {
                mOrientationHandler.disable();
                mOrientationHandler = null;
            }
            releaseMediaRecorder(true /* cleanupFile */);
            mFocusOverlayManager.onPreviewStopped();
            return;
        }
        try {
            mCamera.stopPreview();
            updateCameraOrientation();

            final Camera.Parameters params = mCamera.getParameters();
            final Camera.Size pictureSize = chooseBestPictureSize();
            final Camera.Size previewSize = chooseBestPreviewSize();
            params.setPreviewSize(previewSize.width, previewSize.height);
            params.setPictureSize(pictureSize.width, pictureSize.height);
            logCameraSize("Setting preview size: ", previewSize);
            logCameraSize("Setting picture size: ", pictureSize);
            mCameraPreview.setSize(previewSize, mCameraInfo.orientation);
            for (final String focusMode : params.getSupportedFocusModes()) {
                if (TextUtils.equals(focusMode, Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    // Use continuous focus if available
                    params.setFocusMode(focusMode);
                    break;
                }
            }

            mCamera.setParameters(params);
            mCameraPreview.startPreview(mCamera);
            mCamera.startPreview();
            mCamera.setAutoFocusMoveCallback(new Camera.AutoFocusMoveCallback() {
                @Override
                public void onAutoFocusMoving(final boolean start, final Camera camera) {
                    mFocusOverlayManager.onAutoFocusMoving(start);
                }
            });
            mFocusOverlayManager.setParameters(mCamera.getParameters());
            mFocusOverlayManager.setMirror(mCameraInfo.facing == CameraInfo.CAMERA_FACING_BACK);
            mFocusOverlayManager.onPreviewStarted();
            tryInitOrCleanupVideoMode();
            if (mOrientationHandler == null) {
                mOrientationHandler = new OrientationHandler(mCameraPreview.getContext());
                mOrientationHandler.enable();
            }
        } catch (final IOException e) {
            Log.e(TAG, "IOException in CameraManager.tryShowPreview", e);
            if (mListener != null) {
                mListener.onCameraError(ERROR_SHOWING_PREVIEW, e);
            }
        } catch (final RuntimeException e) {
            Log.e(TAG, "RuntimeException in CameraManager.tryShowPreview", e);
            if (mListener != null) {
                mListener.onCameraError(ERROR_SHOWING_PREVIEW, e);
            }
        }
    }

    private void tryInitOrCleanupVideoMode() {
        if (!mVideoModeRequested || mCamera == null || mCameraPreview == null) {
            releaseMediaRecorder(true /* cleanupFile */);
            return;
        }

        if (mMediaRecorder != null) {
            return;
        }

        try {
            mCamera.unlock();
            final int maxMessageSize = 1024 * 1024;//FIXME
            mMediaRecorder = new MmsVideoRecorder(mCamera, mCameraIndex, mRotation, maxMessageSize);
            mMediaRecorder.prepare();
        } catch (final FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException in CameraManager.tryInitOrCleanupVideoMode", e);
            if (mListener != null) {
                mListener.onCameraError(ERROR_STORAGE_FAILURE, e);
            }
            setVideoMode(false);
            return;
        } catch (final IOException e) {
            Log.e(TAG, "IOException in CameraManager.tryInitOrCleanupVideoMode", e);
            if (mListener != null) {
                mListener.onCameraError(ERROR_INITIALIZING_VIDEO, e);
            }
            setVideoMode(false);
            return;
        } catch (final RuntimeException e) {
            Log.e(TAG, "RuntimeException in CameraManager.tryInitOrCleanupVideoMode", e);
            if (mListener != null) {
                mListener.onCameraError(ERROR_INITIALIZING_VIDEO, e);
            }
            setVideoMode(false);
            return;
        }

        tryStartVideoCapture();
    }

    private void tryStartVideoCapture() {
        if (mMediaRecorder == null || mVideoCallback == null) {
            return;
        }

        mMediaRecorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
            @Override
            public void onError(final MediaRecorder mediaRecorder, final int what,
                                final int extra) {
                if (mListener != null) {
                    mListener.onCameraError(ERROR_RECORDING_VIDEO, null);
                }
            }
        });

        mMediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
            @Override
            public void onInfo(final MediaRecorder mediaRecorder, final int what, final int extra) {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    stopVideo();
                }
            }
        });

        try {
            mMediaRecorder.start();
        } catch (final IllegalStateException e) {
            Log.e(TAG, "IllegalStateException in CameraManager.tryStartVideoCapture", e);
            if (mListener != null) {
                mListener.onCameraError(ERROR_RECORDING_VIDEO, e);
            }
            setVideoMode(false);
        } catch (final RuntimeException e) {
            Log.e(TAG, "RuntimeException in CameraManager.tryStartVideoCapture", e);
            if (mListener != null) {
                mListener.onCameraError(ERROR_RECORDING_VIDEO, e);
            }
            setVideoMode(false);
        }
    }

    void stopVideo() {
        int width = -1;
        int height = -1;
        Uri uri = null;
        String contentType = null;
        try {
//            final Activity activity = Factory.get().getTopActivity();
//            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mMediaRecorder.stop();
            width = mMediaRecorder.getVideoWidth();
            height = mMediaRecorder.getVideoHeight();
            uri = mMediaRecorder.getVideoUri();
            contentType = mMediaRecorder.getContentType();
        } catch (final RuntimeException e) {
            // MediaRecorder.stop will throw a RuntimeException if the video was too short, let the
            // finally clause call the callback with null uri and handle cleanup
            Log.e(TAG, "RuntimeException in CameraManager.stopVideo", e);
        } finally {
            final MediaCallback videoCallback = mVideoCallback;
            mVideoCallback = null;
            releaseMediaRecorder(false /* cleanupFile */);
            if (uri == null) {
                tryInitOrCleanupVideoMode();
            }
            videoCallback.onMediaReady(uri, contentType, width, height);
        }
    }

    boolean isCameraAvailable() {
        return mCamera != null && !mTakingPicture && mIsHardwareAccelerationSupported;
    }

    /**
     * External components call into this to report if hardware acceleration is supported.  When
     * hardware acceleration isn'data supported, we need to report an error through the listener
     * interface
     *
     * @param isHardwareAccelerationSupported True if the preview is rendering in a hardware
     *                                        accelerated view.
     */
    void reportHardwareAccelerationSupported(final boolean isHardwareAccelerationSupported) {
        if (mIsHardwareAccelerationSupported == isHardwareAccelerationSupported) {
            // If the value hasn'data changed nothing more to do
            return;
        }

        mIsHardwareAccelerationSupported = isHardwareAccelerationSupported;
        if (!isHardwareAccelerationSupported) {
            Log.e(TAG, "Software rendering - cannot open camera");
            if (mListener != null) {
                mListener.onCameraError(ERROR_HARDWARE_ACCELERATION_DISABLED, null);
            }
        }
    }

    /**
     * Returns the scale factor to scale the width/height to max allowed in Config
     */
    private float getScaleFactorForMaxAllowedSize(final int width, final int height,
                                                  final int maxWidth, final int maxHeight) {
        if (maxWidth <= 0 || maxHeight <= 0) {
            // MmsConfig initialization runs asynchronously on application startup, so there's a
            // chance (albeit a very slight one) that we don'data have it yet.
            Log.w(TAG, "Max image size not loaded in MmsConfig");
            return 1.0f;
        }

        if (width <= maxWidth && height <= maxHeight) {
            // Already meeting requirements.
            return 1.0f;
        }

        return Math.min(maxWidth * 1.0f / width, maxHeight * 1.0f / height);
    }

    /**
     * Choose the best picture size by trying to find a size close to the MmsConfig's max size,
     * which is closest to the screen aspect ratio
     */
    private Camera.Size chooseBestPictureSize() {
        final Context context = mCameraPreview.getContext();
        final Resources resources = context.getResources();
        final DisplayMetrics displayMetrics = resources.getDisplayMetrics();
        final int displayOrientation = resources.getConfiguration().orientation;
        int cameraOrientation = mCameraInfo.orientation;

        int screenWidth;
        int screenHeight;
        if (displayOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Rotate the camera orientation 90 degrees to compensate for the rotated display
            // metrics. Direction doesn'data matter because we're just using it for width/height
            cameraOrientation += 90;
        }

        // Check the camera orientation relative to the display.
        // For 0, 180, 360, the screen width/height are the display width/height
        // For 90, 270, the screen width/height are inverted from the display
        if (cameraOrientation % 180 == 0) {
            screenWidth = displayMetrics.widthPixels;
            screenHeight = displayMetrics.heightPixels;
        } else {
            screenWidth = displayMetrics.heightPixels;
            screenHeight = displayMetrics.widthPixels;
        }

        final int maxWidth = screenWidth / 3;
        final int maxHeight = screenHeight / 3;

        // Constrain the size within the max width/height defined by MmsConfig.
        final float scaleFactor = getScaleFactorForMaxAllowedSize(screenWidth, screenHeight,
                maxWidth, maxHeight);
        screenWidth *= scaleFactor;
        screenHeight *= scaleFactor;

        final float aspectRatio = 0.5f;//FIXME
        final List<Camera.Size> sizes = new ArrayList<Camera.Size>(
                mCamera.getParameters().getSupportedPictureSizes());
        final int maxPixels = maxWidth * maxHeight;

        // Sort the sizes so the best size is first
        Collections.sort(sizes, new SizeComparator(maxWidth, maxHeight, aspectRatio, maxPixels));

        return sizes.get(0);
    }

    /**
     * Chose the best preview size based on the picture size.  Try to find a size with the same
     * aspect ratio and size as the picture if possible
     */
    private Camera.Size chooseBestPreviewSize() {
        final List<Camera.Size> sizes = new ArrayList<>(
                mCamera.getParameters().getSupportedPreviewSizes());
        return sizes.get(sizes.size() - 1);
    }

    @Override // From FocusOverlayManager.Listener
    public void autoFocus() {
        if (mCamera == null) {
            return;
        }

        try {
            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(final boolean success, final Camera camera) {
                    mFocusOverlayManager.onAutoFocus(success, false /* shutterDown */);
                }
            });
        } catch (final RuntimeException e) {
            Log.e(TAG, "RuntimeException in CameraManager.autoFocus", e);
            // If autofocus fails, the camera should have called the callback with success=false,
            // but some throw an exception here
            mFocusOverlayManager.onAutoFocus(false /*success*/, false /*shutterDown*/);
        }
    }

    @Override // From FocusOverlayManager.Listener
    public void cancelAutoFocus() {
        if (mCamera == null) {
            return;
        }
        try {
            mCamera.cancelAutoFocus();
        } catch (final RuntimeException e) {
            // Ignore
            Log.e(TAG, "RuntimeException in CameraManager.cancelAutoFocus", e);
        }
    }

    @Override // From FocusOverlayManager.Listener
    public boolean capture() {
        return false;
    }

    public void captureSaveAsync(final PictureCallback callback) {
        if (mCamera != null && mCamera.previewEnabled()) {
            XExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCamera.takePicture(null, null, new Camera.PictureCallback() {
                        @Override
                        public void onPictureTaken(byte[] data, Camera camera) {
                            Log.i(TAG, "onPictureTaken...");
                            try {
                                Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                                File picDir = XSettings.getPhotosDir(XApp.getApp());
                                if (!picDir.exists()) {
                                    if (!picDir.mkdir()) {
                                        callback.onFail(new IOException("Fail mkdir"));
                                        return;
                                    }
                                }
                                String picUrl = picDir.getPath() + File.separator + UUID.randomUUID().toString();
                                File myCaptureFile = new File(picUrl);
                                BufferedOutputStream bos = new BufferedOutputStream(
                                        new FileOutputStream(myCaptureFile));
                                bm.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                                bos.flush();
                                bos.close();

                                // Insert.
                                DaoSession daoSession = DaoManager.getInstance().getSession(XApp.getApp());
                                if (daoSession != null) {
                                    AccessInfo accessInfo = new AccessInfo();
                                    accessInfo.setUrl(picUrl);
                                    accessInfo.setWhen(System.currentTimeMillis());
                                    daoSession.getAccessInfoDao().insert(accessInfo);
                                }

                                callback.onImageReady(picUrl);
                            } catch (Exception e) {
                                callback.onFail(e);
                            }
                        }
                    });
                }
            });
        }
    }

    public interface PictureCallback {
        void onImageReady(String path);

        void onFail(Exception e);
    }

    @Override // From FocusOverlayManager.Listener
    public void setFocusParameters() {
        if (mCamera == null) {
            return;
        }
        try {
            final Camera.Parameters parameters = mCamera.getParameters();
            parameters.setFocusMode(mFocusOverlayManager.getFocusMode());
            if (parameters.getMaxNumFocusAreas() > 0) {
                // Don'data set focus areas (even to null) if focus areas aren'data supported, camera may
                // crash
                parameters.setFocusAreas(mFocusOverlayManager.getFocusAreas());
            }
            parameters.setMeteringAreas(mFocusOverlayManager.getMeteringAreas());
            mCamera.setParameters(parameters);
        } catch (final RuntimeException e) {
            // This occurs when the device is out of space or when the camera is locked
            Log.e(TAG, "RuntimeException in CameraManager setFocusParameters");
        }
    }

    private void logCameraSize(final String prefix, final Camera.Size size) {
        // Log the camera size and aspect ratio for help when examining bug reports for camera
        // failures
        Log.i(TAG, prefix + size.width + "x" + size.height +
                " (" + (size.width / (float) size.height) + ")");
    }

    /**
     * Wrapper around the framework camera API to allow mocking different hardware scenarios while
     * unit testing
     */
    interface CameraWrapper {
        int getNumberOfCameras();

        void getCameraInfo(int index, CameraInfo cameraInfo);

        Camera open(int cameraId);

        /**
         * Add a wrapper for release because a final method cannot be mocked
         */
        void release(Camera camera);
    }


    /**
     * Callbacks for the camera manager listener
     */
    interface CameraManagerListener {
        void onCameraError(int errorCode, Exception e);

        void onCameraChanged();
    }

    /**
     * Callback when taking image or video
     */
    interface MediaCallback {
        static final int MEDIA_CAMERA_CHANGED = 1;
        static final int MEDIA_NO_DATA = 2;

        void onMediaReady(Uri uriToMedia, String contentType, int width, int height);

        void onMediaFailed(Exception exception);

        void onMediaInfo(int what);
    }

    private static class SizeComparator implements Comparator<Camera.Size> {
        private static final int PREFER_LEFT = -1;
        private static final int PREFER_RIGHT = 1;

        // The max width/height for the preferred size. Integer.MAX_VALUE if no size limit
        private final int mMaxWidth;
        private final int mMaxHeight;

        // The desired aspect ratio
        private final float mTargetAspectRatio;

        // The desired size (width x height) to try to match
        private final int mTargetPixels;

        public SizeComparator(final int maxWidth, final int maxHeight,
                              final float targetAspectRatio, final int targetPixels) {
            mMaxWidth = maxWidth;
            mMaxHeight = maxHeight;
            mTargetAspectRatio = targetAspectRatio;
            mTargetPixels = targetPixels;
        }

        /**
         * Returns a negative value if left is a better choice than right, or a positive value if
         * right is a better choice is better than left.  0 if they are equal
         */
        @Override
        public int compare(final Camera.Size left, final Camera.Size right) {
            // If one size is less than the max size prefer it over the other
            if ((left.width <= mMaxWidth && left.height <= mMaxHeight) !=
                    (right.width <= mMaxWidth && right.height <= mMaxHeight)) {
                return left.width <= mMaxWidth ? PREFER_LEFT : PREFER_RIGHT;
            }

            // If one is closer to the target aspect ratio, prefer it.
            final float leftAspectRatio = left.width / (float) left.height;
            final float rightAspectRatio = right.width / (float) right.height;
            final float leftAspectRatioDiff = Math.abs(leftAspectRatio - mTargetAspectRatio);
            final float rightAspectRatioDiff = Math.abs(rightAspectRatio - mTargetAspectRatio);
            if (leftAspectRatioDiff != rightAspectRatioDiff) {
                return (leftAspectRatioDiff - rightAspectRatioDiff) < 0 ?
                        PREFER_LEFT : PREFER_RIGHT;
            }

            // At this point they have the same aspect ratio diff and are either both bigger
            // than the max size or both smaller than the max size, so prefer the one closest
            // to target size
            final int leftDiff = Math.abs((left.width * left.height) - mTargetPixels);
            final int rightDiff = Math.abs((right.width * right.height) - mTargetPixels);
            return leftDiff - rightDiff;
        }
    }

    private class OrientationHandler extends OrientationEventListener {
        OrientationHandler(final Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(final int orientation) {
            updateCameraOrientation();
        }
    }
}
