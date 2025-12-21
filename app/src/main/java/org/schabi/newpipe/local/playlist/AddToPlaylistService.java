package org.schabi.newpipe.local.playlist;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
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
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * A foreground service that adds a video to a playlist in the background.
 * Allowing the user to leave the source app (e.g., YouTube) immediately
 * after sharing.
 */
public class AddToPlaylistService extends IntentService {

    public static final String KEY_SERVICE_ID = "key_service_id";
    public static final String KEY_URL = "key_url";
    private static final int NOTIFICATION_ID = 457;

    private Disposable disposable;

    public AddToPlaylistService() {
        super(AddToPlaylistService.class.getSimpleName());
    }

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
    protected void onHandleIntent(@Nullable final Intent intent) {
        if (intent == null) {
            return;
        }

        final int serviceId = intent.getIntExtra(KEY_SERVICE_ID, -1);
        final String url = intent.getStringExtra(KEY_URL);

        if (serviceId == -1 || url == null) {
            return;
        }

        addToPlaylist(serviceId, url);
    }

    private void addToPlaylist(final int serviceId, final String url) {
        // Fetch stream info
        disposable = ExtractorHelper.getStreamInfo(serviceId, url, false)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                        info -> handleStreamInfo(info, url),
                        throwable -> handleError(throwable, url, serviceId)
                );

        try {
            while (disposable != null && !disposable.isDisposed()) {
                Thread.sleep(100);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleStreamInfo(final StreamInfo info, final String url) {
        final LocalPlaylistManager playlistManager =
                new LocalPlaylistManager(NewPipeDatabase.getInstance(this));

        playlistManager.getPlaylistDuplicates(url)
                .firstElement()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                        playlists -> handlePlaylists(playlists, info, playlistManager),
                        throwable -> handleError(throwable, url, info.getServiceId())
                );
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

            playlistManager.appendToPlaylist(playlist.getUid(), streams)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            ignored -> showSuccessToast(playlist),
                            throwable -> handleError(throwable, info.getUrl(), info.getServiceId())
                    );
        } else {
            showToastOnMainThread(getString(R.string.playlist_add_stream_multiple_playlists));
        }

        if (disposable != null) {
            disposable.dispose();
        }
    }

    private void showSuccessToast(final PlaylistDuplicatesEntry playlist) {
        final String toastText;
        if (playlist.getTimesStreamIsContained() > 0) {
            toastText = getString(R.string.playlist_add_stream_success_duplicate,
                    playlist.getTimesStreamIsContained());
        } else {
            toastText = getString(R.string.playlist_add_stream_success);
        }
        showToastOnMainThread(toastText);
    }

    private void showToastOnMainThread(final String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
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

        if (disposable != null) {
            disposable.dispose();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        if (disposable != null) {
            disposable.dispose();
        }
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
