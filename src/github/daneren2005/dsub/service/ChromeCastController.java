/*
  This file is part of Subsonic.
	Subsonic is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	Subsonic is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU General Public License for more details.
	You should have received a copy of the GNU General Public License
	along with Subsonic. If not, see <http://www.gnu.org/licenses/>.
	Copyright 2014 (C) Scott Jackson
*/

package github.daneren2005.dsub.service;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.RemoteMediaPlayer;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.common.images.WebImage;

import java.io.IOException;

import github.daneren2005.dsub.R;
import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.PlayerState;
import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.Util;
import github.daneren2005.dsub.util.compat.CastCompat;

/**
 * Created by owner on 2/9/14.
 */
public class ChromeCastController extends RemoteController {
	private static final String TAG = ChromeCastController.class.getSimpleName();

	private CastDevice castDevice;
	private GoogleApiClient apiClient;
	private ConnectionCallbacks connectionCallbacks;
	private ConnectionFailedListener connectionFailedListener;
	private Cast.Listener castClientListener;

	private boolean applicationStarted = false;
	private boolean waitingForReconnect = false;
	private boolean error = false;

	private RemoteMediaPlayer mediaPlayer;
	private double gain = 0.5;

	public ChromeCastController(DownloadServiceImpl downloadService, CastDevice castDevice) {
		downloadService.setPlayerState(PlayerState.PREPARING);
		this.downloadService = downloadService;
		this.castDevice = castDevice;

		connectionCallbacks = new ConnectionCallbacks();
		connectionFailedListener = new ConnectionFailedListener();
		castClientListener = new Cast.Listener() {
			@Override
			public void onApplicationStatusChanged() {
				if (apiClient != null) {
					Log.d(TAG, "onApplicationStatusChanged: " + Cast.CastApi.getApplicationStatus(apiClient));
				}
			}

			@Override
			public void onVolumeChanged() {
				if (apiClient != null) {
					gain = Cast.CastApi.getVolume(apiClient);
				}
			}

			@Override
			public void onApplicationDisconnected(int errorCode) {
				shutdown();
			}

		};

		Cast.CastOptions.Builder apiOptionsBuilder = Cast.CastOptions.builder(castDevice, castClientListener);
		apiClient = new GoogleApiClient.Builder(downloadService)
			.addApi(Cast.API, apiOptionsBuilder.build())
			.addConnectionCallbacks(connectionCallbacks)
			.addOnConnectionFailedListener(connectionFailedListener)
			.build();

		apiClient.connect();
	}

	@Override
	public void start() {
		if(error) {
			error = false;
			Log.w(TAG, "Attempting to restart song");
			startSong(downloadService.getCurrentPlaying(), true);
			return;
		}

		try {
			mediaPlayer.play(apiClient);
		} catch(Exception e) {
			Log.e(TAG, "Failed to start");
		}
	}

	@Override
	public void stop() {
		try {
			mediaPlayer.pause(apiClient);
		} catch(Exception e) {
			Log.e(TAG, "Failed to pause");
		}
	}

	@Override
	public void shutdown() {
		try {
			if(mediaPlayer != null && !error) {
				mediaPlayer.stop(apiClient);
			}
		} catch(Exception e) {
			Log.e(TAG, "Failed to stop mediaPlayer", e);
		}

		try {
			if(apiClient != null) {
				Cast.CastApi.stopApplication(apiClient);
				Cast.CastApi.removeMessageReceivedCallbacks(apiClient, mediaPlayer.getNamespace());
				mediaPlayer = null;
				applicationStarted = false;
			}
		} catch(Exception e) {
			Log.e(TAG, "Failed to shutdown application", e);
		}

		if(apiClient != null && apiClient.isConnected()) {
			apiClient.disconnect();
		}
		apiClient = null;
	}

	@Override
	public void updatePlaylist() {

	}

	@Override
	public void changePosition(int seconds) {
		try {
			mediaPlayer.seek(apiClient, seconds * 1000L);
		} catch(Exception e) {
			Log.e(TAG, "FAiled to seek to " + seconds);
		}
	}

	@Override
	public void changeTrack(int index, DownloadFile song) {
		startSong(song, true);
	}

	@Override
	public void setVolume(boolean up) {
		double delta = up ? 0.1 : -0.1;
		gain += delta;
		gain = Math.max(gain, 0.0);
		gain = Math.min(gain, 1.0);

		getVolumeToast().setVolume((float) gain);
		try {
			Cast.CastApi.setVolume(apiClient, gain);
		} catch(Exception e) {
			Log.e(TAG, "Failed to the volume");
		}
	}

	@Override
	public int getRemotePosition() {
		if(mediaPlayer != null) {
			return (int) (mediaPlayer.getApproximateStreamPosition() / 1000L);
		} else {
			return 0;
		}
	}

	void startSong(DownloadFile currentPlaying, boolean autoStart) {
		if(currentPlaying == null) {
			// Don't start anything
			return;
		}
		downloadService.setPlayerState(PlayerState.PREPARING);
		MusicDirectory.Entry song = currentPlaying.getSong();

		try {
			MusicService musicService = MusicServiceFactory.getMusicService(downloadService);
			String url = song.isVideo() ? musicService.getHlsUrl(song.getId(), currentPlaying.getBitRate(), downloadService) : musicService.getMusicUrl(downloadService, song, currentPlaying.getBitRate());
			//  Use separate profile for Chromecast so users can do ogg on phone, mp3 for CC
			url = url.replace(Constants.REST_CLIENT_ID, Constants.CHROMECAST_CLIENT_ID);

			// Setup song/video information
			MediaMetadata meta = new MediaMetadata(song.isVideo() ? MediaMetadata.MEDIA_TYPE_MOVIE : MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
			meta.putString(MediaMetadata.KEY_TITLE, song.getTitle());
			if(song.getTrack() != null) {
				meta.putInt(MediaMetadata.KEY_TRACK_NUMBER, song.getTrack());
			}
			if(!song.isVideo()) {
				meta.putString(MediaMetadata.KEY_ARTIST, song.getArtist());
				meta.putString(MediaMetadata.KEY_ALBUM_ARTIST, song.getArtist());
				meta.putString(MediaMetadata.KEY_ALBUM_TITLE, song.getAlbum());
				String coverArt = musicService.getCoverArtUrl(downloadService, song);
				meta.addImage(new WebImage(Uri.parse(coverArt)));
			}

			String contentType;
			if(song.isVideo()) {
				contentType = "application/x-mpegURL";
			}
			else if(song.getTranscodedContentType() != null) {
				contentType = song.getTranscodedContentType();
			} else {
				contentType = song.getContentType();
			}

			// Load it into a MediaInfo wrapper
			MediaInfo mediaInfo = new MediaInfo.Builder(url)
				.setContentType(contentType)
				.setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
				.setMetadata(meta)
				.build();

			mediaPlayer.load(apiClient, mediaInfo, autoStart).setResultCallback(new ResultCallback<RemoteMediaPlayer.MediaChannelResult>() {
				@Override
				public void onResult(RemoteMediaPlayer.MediaChannelResult result) {
					if (result.getStatus().isSuccess()) {
						if(mediaPlayer.getMediaStatus().getPlayerState() == MediaStatus.PLAYER_STATE_PLAYING) {
							downloadService.setPlayerState(PlayerState.STARTED);
						} else {
							downloadService.setPlayerState(PlayerState.PREPARED);
						}
					} else if(result.getStatus().getStatusCode() != ConnectionResult.SIGN_IN_REQUIRED) {
						Log.e(TAG, "Failed to load: " + result.getStatus().toString());
						failedLoad();
					}
				}
			});
		} catch (IllegalStateException e) {
			Log.e(TAG, "Problem occurred with media during loading", e);
			failedLoad();
		} catch (Exception e) {
			Log.e(TAG, "Problem opening media during loading", e);
			failedLoad();
		}
	}

	private void failedLoad() {
		Util.toast(downloadService, downloadService.getResources().getString(R.string.download_failed_to_load));
		downloadService.setPlayerState(PlayerState.STOPPED);
		error = true;
	}


	private class ConnectionCallbacks implements GoogleApiClient.ConnectionCallbacks {
		@Override
		public void onConnected(Bundle connectionHint) {
			if (waitingForReconnect) {
				waitingForReconnect = false;
				// reconnectChannels();
			} else {
				launchApplication();
			}
		}

		@Override
		public void onConnectionSuspended(int cause) {
			waitingForReconnect = true;
		}

		void launchApplication() {
			try {
				Cast.CastApi.launchApplication(apiClient, CastCompat.APPLICATION_ID, false).setResultCallback(new ResultCallback<Cast.ApplicationConnectionResult>() {
					@Override
					public void onResult(Cast.ApplicationConnectionResult result) {
						Status status = result.getStatus();
						if (status.isSuccess()) {
							ApplicationMetadata applicationMetadata = result.getApplicationMetadata();
							String sessionId = result.getSessionId();
							String applicationStatus = result.getApplicationStatus();
							boolean wasLaunched = result.getWasLaunched();

							applicationStarted = true;
							setupChannel();
						} else {
							shutdown();
						}
					}
				});
			} catch (Exception e) {
				Log.e(TAG, "Failed to launch application", e);
			}
		}
		void setupChannel() {
			mediaPlayer = new RemoteMediaPlayer();
			mediaPlayer.setOnStatusUpdatedListener(new RemoteMediaPlayer.OnStatusUpdatedListener() {
				@Override
				public void onStatusUpdated() {
					MediaStatus mediaStatus = mediaPlayer.getMediaStatus();
					switch(mediaStatus.getPlayerState()) {
						case MediaStatus.PLAYER_STATE_PLAYING:
							downloadService.setPlayerState(PlayerState.STARTED);
							break;
						case MediaStatus.PLAYER_STATE_PAUSED:
							downloadService.setPlayerState(PlayerState.PAUSED);
							break;
						case MediaStatus.PLAYER_STATE_BUFFERING:
							downloadService.setPlayerState(PlayerState.PREPARING);
							break;
						case MediaStatus.PLAYER_STATE_IDLE:
							if(mediaStatus.getIdleReason() == MediaStatus.IDLE_REASON_FINISHED) {
								downloadService.setPlayerState(PlayerState.COMPLETED);
								downloadService.next();
							} else {
								downloadService.setPlayerState(PlayerState.IDLE);
							}
							break;
					}
				}
			});
			mediaPlayer.setOnMetadataUpdatedListener(new RemoteMediaPlayer.OnMetadataUpdatedListener() {
				@Override
				public void onMetadataUpdated() {
					MediaInfo mediaInfo = mediaPlayer.getMediaInfo();
					// TODO: Do I care about this?
				}
			});

			try {
				Cast.CastApi.setMessageReceivedCallbacks(apiClient, mediaPlayer.getNamespace(), mediaPlayer);
			} catch (IOException e) {
				Log.e(TAG, "Exception while creating channel", e);
			}

			DownloadFile currentPlaying = downloadService.getCurrentPlaying();
			startSong(currentPlaying, true);
		}
	}

	private class ConnectionFailedListener implements GoogleApiClient.OnConnectionFailedListener {
		@Override
		public void onConnectionFailed(ConnectionResult result) {
			shutdown();
		}
	}
}