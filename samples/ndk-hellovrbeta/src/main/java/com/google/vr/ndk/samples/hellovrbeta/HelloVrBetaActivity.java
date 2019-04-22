/* Copyright 2018 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.vr.ndk.samples.hellovrbeta;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import com.google.vr.ndk.base.AndroidCompat;
import com.google.vr.ndk.base.BufferSpec;
import com.google.vr.ndk.base.BufferViewport;
import com.google.vr.ndk.base.BufferViewportList;
import com.google.vr.ndk.base.Frame;
import com.google.vr.ndk.base.GvrApi;
import com.google.vr.ndk.base.GvrLayout;
import com.google.vr.ndk.base.SwapChain;

import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A Google VR Beta NDK sample application. This app only works for standalone Mirage Solo headset.
 *
 * <p>This app presents a scene consisting of a floating object and passthrough layer, which enables
 * the user to see the environment around them. When the user finds the object, they can invoke the
 * trigger action, and a new object will be randomly spawned. The user can use the controller to
 * position the cursor, and use the controller buttons to invoke the trigger action.
 *
 * <p>This is the main Activity for the sample application. It initializes a GLSurfaceView to allow
 * rendering, a GvrLayout for GVR API access, and forwards relevant events to the native renderer
 * where rendering and interaction are handled.
 */
public class HelloVrBetaActivity extends AlvrActivity {
  static {
    System.loadLibrary("gvr");
    System.loadLibrary("gvr_audio");
    System.loadLibrary("hellovrbeta_jni");
    System.loadLibrary("native-lib");
  }

  // Opaque native pointer to the native HelloVrBetaApp instance.
  // This object is owned by the HelloVrBetaActivity instance and passed to the native methods.
  private long nativeApp;

  private GvrLayout gvrLayout;
  private GLSurfaceView surfaceView;
  private Renderer mRenderer;

  // Note that pause and resume signals to the native renderer are performed on the GL thread,
  // ensuring thread-safety.
  private final Runnable pauseNativeRunnable =
      new Runnable() {
        @Override
        public void run() {
          nativeOnPause(nativeApp);
        }
      };

  private final Runnable resumeNativeRunnable =
      new Runnable() {
        @Override
        public void run() {
          nativeOnResume(nativeApp);
        }
      };

  @Override
  public String getAppName() {
    return getString(R.string.app_name);
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // Ensure fullscreen immersion.
    setImmersiveSticky();
    getWindow()
        .getDecorView()
        .setOnSystemUiVisibilityChangeListener(
            new View.OnSystemUiVisibilityChangeListener() {
              @Override
              public void onSystemUiVisibilityChange(int visibility) {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                  setImmersiveSticky();
                }
              }
            });

    // Initialize GvrLayout and the native renderer.
    gvrLayout = new GvrLayout(this);
    nativeApp =
        nativeOnCreate(
            getClass().getClassLoader(),
            this.getApplicationContext(),
            getAssets(),
            gvrLayout.getGvrApi().getNativeGvrContext());

    // Add the GLSurfaceView to the GvrLayout.
    surfaceView = new GLSurfaceView(this);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 0, 0, 0);
    surfaceView.setPreserveEGLContextOnPause(true);
    mRenderer = new Renderer(gvrLayout.getGvrApi());
    //surfaceView.setRenderer(mRenderer);
    surfaceView.setRenderer(new HelloRenderer(gvrLayout.getGvrApi()));
    gvrLayout.setPresentationView(surfaceView);

    // Add the GvrLayout to the View hierarchy.
    setContentView(gvrLayout);

    // Enable async reprojection.
    if (gvrLayout.setAsyncReprojectionEnabled(true)) {
      // Async reprojection decouples the app framerate from the display framerate,
      // allowing immersive interaction even at the throttled clockrates set by
      // sustained performance mode.
      AndroidCompat.setSustainedPerformanceMode(this, true);
    }

    // Enable VR Mode.
    AndroidCompat.setVrModeEnabled(this, true);
  }

  @Override
  protected void onPause() {
    surfaceView.queueEvent(pauseNativeRunnable);
    surfaceView.onPause();
    gvrLayout.onPause();
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    gvrLayout.onResume();
    surfaceView.onResume();
    surfaceView.queueEvent(resumeNativeRunnable);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    // Destruction order is important; shutting down the GvrLayout will detach
    // the GLSurfaceView and stop the GL thread, allowing safe shutdown of
    // native resources from the UI thread.
    gvrLayout.shutdown();
    nativeOnDestroy(nativeApp);
    nativeApp = 0;
  }

  @Override
  public void onBackPressed() {
    super.onBackPressed();
    gvrLayout.onBackPressed();
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) {
      setImmersiveSticky();
    }
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    // Avoid accidental volume key presses while the phone is in the VR headset.
    if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
        || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
      return true;
    }
    return super.dispatchKeyEvent(event);
  }

  private void setImmersiveSticky() {
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
  }

  private native long nativeOnCreate(
      ClassLoader appClassLoader,
      Context context,
      AssetManager assetManager,
      long nativeGvrContext);

  private native void nativeOnDestroy(long nativeApp);

  private native void nativeOnSurfaceCreated(long nativeApp);

  private native long nativeOnDrawFrame(long nativeApp);

  private native void nativeOnPause(long nativeApp);

  private native void nativeOnResume(long nativeApp);

  private class HelloRenderer implements GLSurfaceView.Renderer{
    private final GvrApi mGvrApi;

    private SwapChain swapChain;
    private final BufferViewportList viewportList;
    private final BufferViewport tmpViewport;
    private final Point targetSize = new Point();
    private final float[] headFromWorld = new float[16];

    private Mesh display;
    private Surface displaySurface;
    private SurfaceTexture displayTexture;
    private final float[] displayMatrix = new float[16];

    public HelloRenderer(GvrApi api) {
      mGvrApi = api;
      viewportList = api.createBufferViewportList();
      tmpViewport = api.createBufferViewport();

      // Create quad that the video is rendered to. Size is based on the render target size.
      display = Mesh.createLrStereoQuad(1.33f, 1.33f, 0);
      Matrix.setIdentityM(displayMatrix, 0);
      Matrix.translateM(displayMatrix, 0, -.33f, -.33f, 0);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
      // Standard initialization
      mGvrApi.initializeGl();
      mGvrApi.getMaximumEffectiveRenderTargetSize(targetSize);
      BufferSpec bufferSpec = mGvrApi.createBufferSpec();
      bufferSpec.setSize(targetSize);
      BufferSpec[] specList = {bufferSpec};
      swapChain = mGvrApi.createSwapChain(specList);
      bufferSpec.shutdown();

      int[] texId = new int[1];
      GLES20.glGenTextures(1, IntBuffer.wrap(texId));
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId[0]);
      GLES20.glTexParameteri(
              GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(
              GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(
              GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(
              GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

      display.glInit(texId[0]);

      displayTexture = new SurfaceTexture(texId[0]);
      displayTexture.setDefaultBufferSize(1024, 1024);
      displaySurface = new Surface(displayTexture);


      vrThread = new VrThread(HelloVrBetaActivity.this, displayTexture, displaySurface, mGvrApi.getNativeGvrContext());
      vrThread.onSurfaceCreated();
      vrThread.start();
      vrThread.onResume();

      nativeOnSurfaceCreated(nativeApp);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {}

    private final float[] invRotationMatrix = new float[16];
    private final float[] translationMatrix = new float[16];

    @Override
    public void onDrawFrame(GL10 gl) {
      Frame frame = swapChain.acquireFrame();
      long poseTime = System.nanoTime();
      mGvrApi.getHeadSpaceFromStartSpaceTransform(headFromWorld, poseTime  + TimeUnit.MILLISECONDS.toNanos(50));
      mGvrApi.getRecommendedBufferViewports(viewportList);
      frame.bindBuffer(0);

      // Extract quaternion since that's what steam expects.
      float[] m = headFromWorld;
      float t0 = m[0] + m[5] + m[10];
      float x, y, z, w;
      if (t0 >= 0) {
        float s = (float) Math.sqrt(t0 + 1);
        w = .5f * s;
        s = .5f / s;
        x = (m[9] - m[6]) * s;
        y = (m[2] - m[8]) * s;
        z = (m[4] - m[1]) * s;
      } else if (m[0] > m[5] && m[0] > m[10]) {
        float s = (float) Math.sqrt(1 + m[0] - m[5] - m[10]);
        x = s * .5f;
        s = .5f / s;
        y = (m[4] + m[1]) * s;
        z = (m[2] + m[8]) * s;
        w = (m[9] - m[6]) * s;
      } else if (m[5] > m[10]) {
        float s = (float) Math.sqrt(1 + m[5] - m[0] - m[10]);
        y = s * .5f;
        s = .5f / s;
        x = (m[4] + m[1]) * s;
        z = (m[9] + m[6]) * s;
        w = (m[2] - m[8]) * s;
      } else {
        float s = (float) Math.sqrt(1 + m[10] - m[0] - m[5]);
        z = s * .5f;
        s = .5f / s;
        x = (m[2] + m[8]) * s;
        y = (m[9] + m[6]) * s;
        w = (m[4] - m[1]) * s;
      }

      // Extract translation. But first undo the rotation.
      Matrix.transposeM(invRotationMatrix, 0, headFromWorld, 0);
      invRotationMatrix[3] = invRotationMatrix[7] = invRotationMatrix[11] = 0;
      Matrix.multiplyMM(translationMatrix, 0, invRotationMatrix, 0, headFromWorld, 0);
      //Log.e("XXX", Arrays.toString(translationMatrix));

      // Set tracking and save the current head pose. The headFromWorld value is saved in frameTracker via a call to trackFrame by the TrackingThread.
      vrThread.setTracking(-translationMatrix[12], 1.8f - translationMatrix[13], -translationMatrix[14], x, y, z, w, headFromWorld, poseTime);
      //Log.e("XXX", "saving frame " + z + " " + Arrays.toString(m) + Math.sqrt(x * x + y * y + z * z + w * w));

      // At this point, the pose is sent to the PC. On some future draw call, we read it back.
      // The code above this point and below this point should actually be in two separate
      // functions and threads.
      //long renderedFrameIndex = vrThread.updateTexImage();
      long renderedFrameIndex = 10;
      Pair<float[], Long> p = frameTracker.get(renderedFrameIndex);
      frameTracker.remove(renderedFrameIndex - 100); // Reduce leaked memory.
      if (p == null) {
        // frames were dropped.
        m = headFromWorld;
      } else {
        m = p.first;
        Log.e("XXX", "using frame " + renderedFrameIndex + "@" + (System.nanoTime() - p.second)/1000000f);
      }

      // Draw quad across both eyes in one shot.
      GLES20.glClearColor(1, 0, 0, 1);
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
      viewportList.get(0, tmpViewport);
      gl.glViewport(0, 0, targetSize.x, targetSize.y);
      //display.glDraw(displayMatrix);

      frame.unbind();
      frame.submit(viewportList, m);

      nativeOnDrawFrame(nativeApp);
    }
  }
  private class Renderer implements GLSurfaceView.Renderer {
    private final GvrApi mGvrApi;

    private SwapChain swapChain;
    private final BufferViewportList viewportList;
    private final BufferViewport tmpViewport;
    private final Point targetSize = new Point();
    private final float[] headFromWorld = new float[16];

    private Mesh display;
    private Surface displaySurface;
    private SurfaceTexture displayTexture;
    private final float[] displayMatrix = new float[16];

    public Renderer(GvrApi api) {
      mGvrApi = api;
      viewportList = api.createBufferViewportList();
      tmpViewport = api.createBufferViewport();

      // Create quad that the video is rendered to. Size is based on the render target size.
      display = Mesh.createLrStereoQuad(1.33f, 1.33f, 0);
      Matrix.setIdentityM(displayMatrix, 0);
      Matrix.translateM(displayMatrix, 0, -.33f, -.33f, 0);
    }

    @Override
    public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
      // Standard initialization
      mGvrApi.initializeGl();
      mGvrApi.getMaximumEffectiveRenderTargetSize(targetSize);
      BufferSpec bufferSpec = mGvrApi.createBufferSpec();
      bufferSpec.setSize(targetSize);
      BufferSpec[] specList = {bufferSpec};
      swapChain = mGvrApi.createSwapChain(specList);
      bufferSpec.shutdown();

      int[] texId = new int[1];
      GLES20.glGenTextures(1, IntBuffer.wrap(texId));
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId[0]);
      GLES20.glTexParameteri(
              GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(
              GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(
              GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(
              GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

      display.glInit(texId[0]);

      displayTexture = new SurfaceTexture(texId[0]);
      displayTexture.setDefaultBufferSize(1024, 1024);
      displaySurface = new Surface(displayTexture);


      vrThread = new VrThread(HelloVrBetaActivity.this, displayTexture, displaySurface, mGvrApi.getNativeGvrContext());
      vrThread.onSurfaceCreated();
      vrThread.start();
      vrThread.onResume();
    }

    @Override
    public void onSurfaceChanged(GL10 gl10, int i, int i1) {

    }

    private final float[] invRotationMatrix = new float[16];
    private final float[] translationMatrix = new float[16];

    @Override
    public void onDrawFrame(GL10 gl10) {
      Frame frame = swapChain.acquireFrame();
      long poseTime = System.nanoTime();
      mGvrApi.getHeadSpaceFromStartSpaceTransform(headFromWorld, poseTime  + TimeUnit.MILLISECONDS.toNanos(50));
      mGvrApi.getRecommendedBufferViewports(viewportList);
      frame.bindBuffer(0);

      // Extract quaternion since that's what steam expects.
      float[] m = headFromWorld;
      float t0 = m[0] + m[5] + m[10];
      float x, y, z, w;
      if (t0 >= 0) {
        float s = (float) Math.sqrt(t0 + 1);
        w = .5f * s;
        s = .5f / s;
        x = (m[9] - m[6]) * s;
        y = (m[2] - m[8]) * s;
        z = (m[4] - m[1]) * s;
      } else if (m[0] > m[5] && m[0] > m[10]) {
        float s = (float) Math.sqrt(1 + m[0] - m[5] - m[10]);
        x = s * .5f;
        s = .5f / s;
        y = (m[4] + m[1]) * s;
        z = (m[2] + m[8]) * s;
        w = (m[9] - m[6]) * s;
      } else if (m[5] > m[10]) {
        float s = (float) Math.sqrt(1 + m[5] - m[0] - m[10]);
        y = s * .5f;
        s = .5f / s;
        x = (m[4] + m[1]) * s;
        z = (m[9] + m[6]) * s;
        w = (m[2] - m[8]) * s;
      } else {
        float s = (float) Math.sqrt(1 + m[10] - m[0] - m[5]);
        z = s * .5f;
        s = .5f / s;
        x = (m[2] + m[8]) * s;
        y = (m[9] + m[6]) * s;
        w = (m[4] - m[1]) * s;
      }

      // Extract translation. But first undo the rotation.
      Matrix.transposeM(invRotationMatrix, 0, headFromWorld, 0);
      invRotationMatrix[3] = invRotationMatrix[7] = invRotationMatrix[11] = 0;
      Matrix.multiplyMM(translationMatrix, 0, invRotationMatrix, 0, headFromWorld, 0);
      //Log.e("XXX", Arrays.toString(translationMatrix));

      // Set tracking and save the current head pose. The headFromWorld value is saved in frameTracker via a call to trackFrame by the TrackingThread.
      vrThread.setTracking(-translationMatrix[12], 1.8f - translationMatrix[13], -translationMatrix[14], x, y, z, w, headFromWorld, poseTime);
      //Log.e("XXX", "saving frame " + z + " " + Arrays.toString(m) + Math.sqrt(x * x + y * y + z * z + w * w));

      // At this point, the pose is sent to the PC. On some future draw call, we read it back.
      // The code above this point and below this point should actually be in two separate
      // functions and threads.
      long renderedFrameIndex = vrThread.updateTexImage();
      Pair<float[], Long> p = frameTracker.get(renderedFrameIndex);
      frameTracker.remove(renderedFrameIndex - 100); // Reduce leaked memory.
      if (p == null) {
        // frames were dropped.
        m = headFromWorld;
      } else {
        m = p.first;
        Log.e("XXX", "using frame " + renderedFrameIndex + "@" + (System.nanoTime() - p.second)/1000000f);
      }

      // Draw quad across both eyes in one shot.
      GLES20.glClearColor(0, 0, 0, 1);
      //GLES20.glClearColor(1, 0, 0, 1);
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
      viewportList.get(0, tmpViewport);
      gl10.glViewport(0, 0, targetSize.x, targetSize.y);
      display.glDraw(displayMatrix);

      frame.unbind();
      frame.submit(viewportList, m);
    }

    public void shutdown() {
      viewportList.shutdown();
      tmpViewport.shutdown();
      swapChain.shutdown();
    }
  }

  // Frames are saved from the TrackerThread. Pretend there is no race condition.
  public static final ConcurrentHashMap<Long, Pair<float[], Long>> frameTracker = new ConcurrentHashMap<Long, Pair<float[], Long>>();
  public static void trackFrame(long frameIndex, float[] matrix, long poseTime) {
    frameTracker.put(frameIndex, Pair.create(matrix.clone(), poseTime));
    Log.e("XXX", "tracking frame " + frameIndex + " " + matrix[2]);
  }
}
