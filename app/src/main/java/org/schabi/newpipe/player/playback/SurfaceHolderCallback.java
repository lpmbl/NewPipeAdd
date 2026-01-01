package org.schabi.newpipe.player.playback;

import android.content.Context;
import android.util.Log;
import android.view.SurfaceHolder;

import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.video.PlaceholderSurface;

/**
 * Prevent error message: 'Unrecoverable player error occurred'
 * In case of rotation some users see this kind of an error which is preventable
 * having a Callback that handles the lifecycle of the surface.
 * <p>
 * How?: In case we are no longer able to write to the surface eg. through rotation/putting in
 * background we set set a DummySurface. Although it it works on API >= 23 only.
 * Result: we get a little video interruption (audio is still fine) but we won't get the
 * 'Unrecoverable player error occurred' error message.
 * <p>
 * This implementation is based on:
 * 'ExoPlayer stuck in buffering after re-adding the surface view a few time #2703'
 * <p>
 * -> exoplayer fix suggestion link
 * https://github.com/google/ExoPlayer/issues/2703#issuecomment-300599981
 */
public final class SurfaceHolderCallback implements SurfaceHolder.Callback {
    private static final String TAG = "SurfaceHolderCallback";

    private final Context context;
    private final Player player;
    private PlaceholderSurface placeholderSurface;

    public SurfaceHolderCallback(final Context context, final Player player) {
        this.context = context;
        this.player = player;
        Log.d(TAG, "SurfaceHolderCallback created");
    }

    @Override
    public void surfaceCreated(final SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated() called, surface=" + holder.getSurface()
                + ", isValid=" + holder.getSurface().isValid());
        player.setVideoSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(final SurfaceHolder holder,
                               final int format,
                               final int width,
                               final int height) {
        Log.d(TAG, "surfaceChanged() called: format=" + format
                + ", width=" + width + ", height=" + height);
        // Reconnect surface if it became valid - fixes black screen when switching videos
        if (holder.getSurface().isValid()) {
            Log.d(TAG, "surfaceChanged: reconnecting valid surface to player");
            player.setVideoSurface(holder.getSurface());
        }
    }

    @Override
    public void surfaceDestroyed(final SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed() called, setting placeholder surface");
        if (placeholderSurface == null) {
            placeholderSurface = PlaceholderSurface.newInstanceV17(context, false);
        }
        player.setVideoSurface(placeholderSurface);
    }

    public void release() {
        Log.d(TAG, "release() called");
        if (placeholderSurface != null) {
            placeholderSurface.release();
            placeholderSurface = null;
        }
    }
}
