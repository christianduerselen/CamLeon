package org.webrtc;

import android.content.Context;
import android.hardware.camera2.CameraManager;

/**
 * CamLeon: libwebrtc's Camera2Capturer, but every session is a {@link FpsCamera2Session} so the
 * requested frame rate picks a matching AE range (60 fps really runs at 60 where the camera has it).
 * Lives in org.webrtc because CameraSession and its callbacks are package-private.
 */
public class FpsCamera2Capturer extends Camera2Capturer {
  private final CameraManager cameraManager;

  public FpsCamera2Capturer(Context context, String cameraName, CameraEventsHandler eventsHandler) {
    super(context, cameraName, eventsHandler);
    cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
  }

  /** Upper bound (fps) of the AE range the camera currently runs with; 0 = not started yet. */
  public static int activeMaxFps() {
    return FpsCamera2Session.activeMaxFps;
  }

  @Override
  protected void createCameraSession(CameraSession.CreateSessionCallback createSessionCallback,
      CameraSession.Events events, Context applicationContext,
      SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height,
      int framerate) {
    FpsCamera2Session.create(createSessionCallback, events, applicationContext, cameraManager,
        surfaceTextureHelper, cameraName, width, height, framerate);
  }
}
