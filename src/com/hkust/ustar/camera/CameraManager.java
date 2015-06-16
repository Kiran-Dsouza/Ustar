package com.hkust.ustar.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.IOException;

import com.hkust.ustar.PlanarYUVLuminanceSource;
import com.hkust.ustar.PreferencesActivity;

/**
 * This object wraps the Camera service object and expects to be the only one talking to it.
 */
public final class CameraManager {

  private static final String TAG = CameraManager.class.getSimpleName();
  
  private static final int MIN_FRAME_WIDTH = 50; // originally 240
  private static final int MIN_FRAME_HEIGHT = 20; // originally 240
  private static final int MAX_FRAME_WIDTH = 800; // originally 480
  private static final int MAX_FRAME_HEIGHT = 600; // originally 360
  
  private final Context context;
  private final CameraConfigurationManager configManager;
  private Camera camera;
  private AutoFocusManager autoFocusManager;
  private Rect framingRect;
  private Rect framingRectInPreview;
  private boolean initialized;
  private boolean previewing;
  private boolean reverseImage;
  private int requestedFramingRectWidth;
  private int requestedFramingRectHeight;
  /**
   * Preview frames are delivered here, which we pass on to the registered handler. Make sure to
   * clear the handler so it will only receive one message.
   */
  private final PreviewCallback previewCallback;

  public CameraManager(Context context) {
    this.context = context;
    this.configManager = new CameraConfigurationManager(context);
    previewCallback = new PreviewCallback(configManager);
  }

  /**
   * Opens the camera driver and initializes the hardware parameters.
   */
  public synchronized void openDriver(SurfaceHolder holder) throws IOException {
    Camera theCamera = camera;
    if (theCamera == null) {
      theCamera = Camera.open();
      if (theCamera == null) {
        throw new IOException();
      }
      camera = theCamera;
    }
    camera.setPreviewDisplay(holder);
    if (!initialized) {
      initialized = true;
      configManager.initFromCameraParameters(theCamera);
      if (requestedFramingRectWidth > 0 && requestedFramingRectHeight > 0) {
        adjustFramingRect(requestedFramingRectWidth, requestedFramingRectHeight);
        requestedFramingRectWidth = 0;
        requestedFramingRectHeight = 0;
      }
    }
    configManager.setDesiredCameraParameters(theCamera);
    
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    reverseImage = prefs.getBoolean(PreferencesActivity.KEY_REVERSE_IMAGE, false);
  }

  /**
   * Closes the camera driver if still in use.
   */
  public synchronized void closeDriver() {
    if (camera != null) {
      camera.release();
      camera = null;

      // Make sure to clear these each time we close the camera, so that any scanning rect
      // requested by intent is forgotten.
      framingRect = null;
      framingRectInPreview = null;
    }
  }

  /**
   * Asks the camera hardware to begin drawing preview frames to the screen.
   */
  public synchronized void startPreview() {
    Camera theCamera = camera;
    if (theCamera != null && !previewing) {
      theCamera.startPreview();
      previewing = true;
      autoFocusManager = new AutoFocusManager(context, camera);
    }
  }

  /**
   * Tells the camera to stop drawing preview frames.
   */
  public synchronized void stopPreview() {
    if (autoFocusManager != null) {
    	autoFocusManager.stop();
    	autoFocusManager = null;
    }
  	if (camera != null && previewing) {
      camera.stopPreview();
      previewCallback.setHandler(null, 0);
      previewing = false;
    }
  }

  /**
   * A single preview frame will be returned to the handler supplied.
   */
  public synchronized void requestOcrDecode(Handler handler, int message) {
    Camera theCamera = camera;
    if (theCamera != null && previewing) {
      previewCallback.setHandler(handler, message);
      theCamera.setOneShotPreviewCallback(previewCallback);
    }
  }
  
  /**
   * Asks the camera hardware to perform an autofocus.
   */
  public synchronized void requestAutoFocus(long delay) {
  	autoFocusManager.start(delay);
  }
  
  /**
   * Calculates the framing rect which the UI should draw to show the user where to place the
   * barcode.
   */
  public synchronized Rect getFramingRect() {
    if (framingRect == null) {
      if (camera == null) {
        return null;
      }
      Point screenResolution = configManager.getScreenResolution();
      if (screenResolution == null) {
        // Called early, before init even finished
        return null;
      }
      int width = screenResolution.x * 3/5;
      if (width < MIN_FRAME_WIDTH) {
        width = MIN_FRAME_WIDTH;
      } else if (width > MAX_FRAME_WIDTH) {
        width = MAX_FRAME_WIDTH;
      }
      int height = screenResolution.y * 1/5;
      if (height < MIN_FRAME_HEIGHT) {
        height = MIN_FRAME_HEIGHT;
      } else if (height > MAX_FRAME_HEIGHT) {
        height = MAX_FRAME_HEIGHT;
      }
      int leftOffset = (screenResolution.x - width) / 2;
      int topOffset = (screenResolution.y - height) / 2;
      framingRect = new Rect(leftOffset, topOffset, leftOffset + width, topOffset + height);
    }
    return framingRect;
  }

  public synchronized Rect getFramingRectInPreview() {
    if (framingRectInPreview == null) {
      Rect rect = new Rect(getFramingRect());
      Point cameraResolution = configManager.getCameraResolution();
      Point screenResolution = configManager.getScreenResolution();
      if (cameraResolution == null || screenResolution == null) {
        // Called early, before init even finished
        return null;
      }
      rect.left = rect.left * cameraResolution.x / screenResolution.x;
      rect.right = rect.right * cameraResolution.x / screenResolution.x;
      rect.top = rect.top * cameraResolution.y / screenResolution.y;
      rect.bottom = rect.bottom * cameraResolution.y / screenResolution.y;
      framingRectInPreview = rect;
    }
    return framingRectInPreview;
  }

  /**
   * Changes the size of the framing rect.
   */
  public synchronized void adjustFramingRect(int deltaWidth, int deltaHeight) {
    if (initialized) {
      Point screenResolution = configManager.getScreenResolution();

      // Set maximum and minimum sizes
      if ((framingRect.width() + deltaWidth > screenResolution.x - 4) || (framingRect.width() + deltaWidth < 50)) {
        deltaWidth = 0;
      }
      if ((framingRect.height() + deltaHeight > screenResolution.y - 4) || (framingRect.height() + deltaHeight < 50)) {
        deltaHeight = 0;
      }

      int newWidth = framingRect.width() + deltaWidth;
      int newHeight = framingRect.height() + deltaHeight;
      int leftOffset = (screenResolution.x - newWidth) / 2;
      int topOffset = (screenResolution.y - newHeight) / 2;
      framingRect = new Rect(leftOffset, topOffset, leftOffset + newWidth, topOffset + newHeight);
      framingRectInPreview = null;
    } else {
      requestedFramingRectWidth = deltaWidth;
      requestedFramingRectHeight = deltaHeight;
    }
  }

  /**
   * A factory method to build the appropriate LuminanceSource object.
   */
  public PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height) {
    Rect rect = getFramingRectInPreview();
    if (rect == null) {
      return null;
    }
    // Go ahead and assume it's YUV rather than die.
    return new PlanarYUVLuminanceSource(data, width, height, rect.left, rect.top,
                                        rect.width(), rect.height(), reverseImage);
  }

  
  /***
   * Attempts to set camera flash torch/flashlight mode on/off.
   */
  public boolean setFlashlight(boolean isOn)
  {
      if (camera == null)
      {
          return false;
      }
      Camera.Parameters params = camera.getParameters();
      String value;
      if (isOn) // we are being ask to turn it on
      {
          value = Camera.Parameters.FLASH_MODE_TORCH;
      }
      else  // we are being asked to turn it off
      {
          value =  Camera.Parameters.FLASH_MODE_AUTO;
      }

      try{    
          params.setFlashMode(value);
          camera.setParameters(params);

          String nowMode = camera.getParameters().getFlashMode();

          if (isOn && nowMode.equals(Camera.Parameters.FLASH_MODE_TORCH))
          {
              return true;
          }
          if (! isOn && nowMode.equals(Camera.Parameters.FLASH_MODE_AUTO))
          {
              return true;
          }
          return false;
      }
      catch (Exception ex)
      {
          Log.e(TAG, this.getClass().getSimpleName() +  " error setting flash mode to: "+ value + " " + ex.toString());
      }
	return isOn;
  }
}