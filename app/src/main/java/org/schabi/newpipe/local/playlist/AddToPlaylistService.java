package org.schabi.newpipe.local.playlist;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * A foreground service that adds a video to a playlist in the background.
 * Allowing the user to leave the source app (e.g., YouTube) immediately
 * after sharing.
 */
public class AddToPlaylistService extends Service {

    public static final String KEY_SERVICE_ID = "key_service_id";
    public static final String KEY_URL = "key_url";
    private static final int NOTIFICATION_ID = 457;

    private final CompositeDisposable disposables = new CompositeDisposable();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Creates an intent to start this service with the given parameters.
     *
     * @param context   the context
     * @param serviceId the streaming service ID (e.g., YouTube = 0)
     * @param url       the URL of the video to add
     * @return the intent to start this service
     */
    public static Intent createIntent(final Context context,
                                       final int serviceId,
                                       final String url) {
        final Intent intent = new Intent(context, AddToPlaylistService.class);
        intent.putExtra(KEY_SERVICE_ID, serviceId);
        intent.putExtra(KEY_URL, url);
        return intent;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, createNotification().build());
    }

    @Override
    public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final int serviceId = intent.getIntExtra(KEY_SERVICE_ID, -1);
        final String url = intent.getStringExtra(KEY_URL);

        if (serviceId == -1 || url == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        addToPlaylist(serviceId, url);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    private void addToPlaylist(final int serviceId, final String url) {
        disposables.add(ExtractorHelper.getStreamInfo(serviceId, url, false)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                        info -> handleStreamInfo(info, url),
                        throwable -> {
                            handleError(throwable, url, serviceId);
                            stopSelf();
                        }
                ));
    }

    private void handleStreamInfo(final StreamInfo info, final String url) {
        final LocalPlaylistManager playlistManager =
                new LocalPlaylistManager(NewPipeDatabase.getInstance(this));

        disposables.add(playlistManager.getPlaylistDuplicates(url)
                .firstElement()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                        playlists -> handlePlaylists(playlists, info, playlistManager),
                        throwable -> {
                            handleError(throwable, url, info.getServiceId());
                            stopSelf();
                        }
                ));
    }

    private void handlePlaylists(final List<PlaylistDuplicatesEntry> playlists,
                                  final StreamInfo info,
                                  final LocalPlaylistManager playlistManager) {
        final boolean autoAddEnabled = PreferenceManager
                .getDefaultSharedPreferences(this)
                .getBoolean(getString(R.string.auto_add_to_single_playlist_key), false);

        if (autoAddEnabled && playlists.size() == 1) {
            final PlaylistDuplicatesEntry playlist = playlists.get(0);
            final List<StreamEntity> streams = List.of(new StreamEntity(info));

            disposables.add(playlistManager.appendToPlaylist(playlist.getUid(), streams)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            insertedIds -> {
                                showSuccessToast(insertedIds);
                                stopSelf();
                            },
                            throwable -> {
                                handleError(throwable, info.getUrl(), info.getServiceId());
                                stopSelf();
                            }
                    ));
        } else {
            showToastOnMainThread(getString(R.string.playlist_add_stream_multiple_playlists));
            stopSelf();
        }
    }

    private void showSuccessToast(final List<Long> insertedIds) {
        final String toastText;
        if (insertedIds.isEmpty()) {
            // Video was a duplicate and skipped
            toastText = getString(R.string.playlist_add_stream_skipped_duplicate);
        } else {
            toastText = getString(R.string.playlist_add_stream_success);
        }
        Toast.makeText(getApplicationContext(), toastText, Toast.LENGTH_SHORT).show();
    }

    private void showToastOnMainThread(final String message) {
        mainHandler.post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show()
        );
    }

    private void handleError(final Throwable throwable, final String url, final int serviceId) {
        ErrorUtil.createNotification(this, new ErrorInfo(
                throwable,
                UserAction.REQUESTED_STREAM,
                "Adding " + url + " to playlist via service",
                serviceId,
                url
        ));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disposables.clear();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    private NotificationCompat.Builder createNotification() {
        return new NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentTitle(getString(R.string.add_to_playlist_notification_title))
                .setContentText(getString(R.string.add_to_playlist_notification_message));
    }
}
