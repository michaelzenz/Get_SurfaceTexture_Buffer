package com.airlife.getsurfacetexturebuffer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.Toast;

import com.airlife.getsurfacetexturebuffer.gles.EglCore;
import com.airlife.getsurfacetexturebuffer.gles.FullFrameRect;
import com.airlife.getsurfacetexturebuffer.gles.OffscreenSurface;
import com.airlife.getsurfacetexturebuffer.gles.Texture2dProgram;
import com.airlife.getsurfacetexturebuffer.gles.WindowSurface;

import java.io.IOException;
import java.lang.ref.WeakReference;


//The demo that use TextureView to get buffer from SurfaceTexture, Works Now
public class CameraWithTextureView extends Activity{
    private static final String TAG = CameraWithTextureView.TAG;

    private static final int VIDEO_WIDTH = 1280;  // dimensions for 720p video
    private static final int VIDEO_HEIGHT = 720;
    private static final int DESIRED_PREVIEW_FPS = 15;

    private EglCore mEglCore;
    private SurfaceTexture mCameraTexture;  // receives the output from the camera preview
    private TextureView displayTextureView;
    private FullFrameRect mFullFrameBlit; //renders the texture
    private float[] mTmpMatrix = null;
    private int mTextureId; //for the camera texture
    Boolean previewStarted=false;

    private Camera mCamera;
    private int mCameraPreviewThousandFps;

    private WindowSurface mWindowSurface;
    private OffscreenSurface mOffscreenSurface;

    private int viewWidth;
    private int viewHeight;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {

        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_textureview);

        displayTextureView=(TextureView) findViewById(R.id.camera_textureview);
        mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);

        displayTextureView.setSurfaceTextureListener(mSurfaceTextureListener);

    }
    @Override
    protected void onResume() {
        super.onResume();

        if (!PermissionHelper.hasCameraPermission(this)) {
            PermissionHelper.requestCameraPermission(this, false);
        }
    }

    SurfaceTexture.OnFrameAvailableListener mFrameAvailableListener=new SurfaceTexture.OnFrameAvailableListener() {
        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            mCameraTexture.updateTexImage();
            if(mTmpMatrix==null){
                mTmpMatrix=new float[16];
                mCameraTexture.getTransformMatrix(mTmpMatrix);
            }

            // Fill the SurfaceView with it.
            mWindowSurface.makeCurrent();
            GLES20.glViewport(0, 0, viewWidth, viewHeight);
            mFullFrameBlit.drawFrame(mTextureId, mTmpMatrix);
            mWindowSurface.swapBuffers();

            mOffscreenSurface.makeCurrent();
            GLES20.glViewport(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);
            mFullFrameBlit.drawFlippedFrame(mTextureId, mTmpMatrix);
            mOffscreenSurface.getPixels();//here you can get the pixels
        }
    };

    TextureView.SurfaceTextureListener mSurfaceTextureListener=new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            viewWidth=width;
            viewHeight=height;

            mWindowSurface=new WindowSurface(mEglCore,surface);
            mWindowSurface.makeCurrent();
            mFullFrameBlit = new FullFrameRect(
                    new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));
            mTextureId = mFullFrameBlit.createTextureObject();
            mCameraTexture=new SurfaceTexture(mTextureId);
            mCameraTexture.setOnFrameAvailableListener(mFrameAvailableListener);

            mOffscreenSurface=new OffscreenSurface(mEglCore,VIDEO_WIDTH,VIDEO_HEIGHT);

            if (mCamera == null) {
                // Ideally, the frames from the camera are at the same resolution as the input to
                // the video encoder so we don't have to scale.
                openCamera(VIDEO_WIDTH, VIDEO_HEIGHT, DESIRED_PREVIEW_FPS);
            }
            if(!previewStarted)startPreview();


        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            //configureTransform(width,height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };


    private void configureTransform(final int viewWidth, final int viewHeight) {
        final int rotation = ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        final float centerX = viewRect.centerX();
        final float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            matrix.setRectToRect(viewRect, viewRect, Matrix.ScaleToFit.FILL);
            matrix.postScale(1, 1, centerX, centerY);
            matrix.postRotate(90 * (rotation - 1), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        displayTextureView.setTransform(matrix);
    }

    private void openCamera(int desiredWidth, int desiredHeight, int desiredFps) {
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                mCamera = Camera.open(i);
                break;
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = mCamera.getParameters();

        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

        // Try to set the frame rate to a constant value.
        mCameraPreviewThousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true);

        mCamera.setParameters(parms);

        Camera.Size cameraPreviewSize = parms.getPreviewSize();
        String previewFacts = cameraPreviewSize.width + "x" + cameraPreviewSize.height +
                " @" + (mCameraPreviewThousandFps / 1000.0f) + "fps";
        Log.i(TAG, "Camera config: " + previewFacts);

        AspectFrameLayout layout = (AspectFrameLayout) findViewById(R.id.textureview_layout);

        Display display = ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

        if(display.getRotation() == Surface.ROTATION_0) {
            mCamera.setDisplayOrientation(90);
            layout.setAspectRatio((double) cameraPreviewSize.height / cameraPreviewSize.width);
        } else if(display.getRotation() == Surface.ROTATION_270) {
            layout.setAspectRatio((double) cameraPreviewSize.height / cameraPreviewSize.width);
            mCamera.setDisplayOrientation(180);
        } else {
            // Set the preview aspect ratio.
            layout.setAspectRatio((double) cameraPreviewSize.width / cameraPreviewSize.height);
        }

    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    private void startPreview() {
        if (mCamera != null) {
            Log.d(TAG, "starting camera preview");
            try {
                mCamera.setPreviewTexture(mCameraTexture);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            mCamera.startPreview();
            previewStarted=true;
        }

    }

    private String chooseCamera() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    continue;
                }

                final StreamConfigurationMap map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (map == null) {
                    continue;
                }
                return cameraId;
            }
        } catch (CameraAccessException e) {
        }

        return null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!PermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this,
                    "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
            PermissionHelper.launchPermissionSettings(this);
            finish();
        } else {
            openCamera(VIDEO_WIDTH, VIDEO_HEIGHT, DESIRED_PREVIEW_FPS);
        }
    }

}
