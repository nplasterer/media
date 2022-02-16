/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.google.android.exoplayer2.ext.ima;

import static com.google.android.exoplayer2.ext.ima.ImaUtil.expandAdGroupPlaceholder;
import static com.google.android.exoplayer2.ext.ima.ImaUtil.getAdGroupAndIndexInMultiPeriodWindow;
import static com.google.android.exoplayer2.ext.ima.ImaUtil.splitAdPlaybackStateForPeriods;
import static com.google.android.exoplayer2.ext.ima.ImaUtil.updateAdDurationAndPropagate;
import static com.google.android.exoplayer2.ext.ima.ImaUtil.updateAdDurationInAdGroup;
import static com.google.android.exoplayer2.source.ads.ServerSideAdInsertionUtil.addAdGroupToAdPlaybackState;
import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import static com.google.android.exoplayer2.util.Assertions.checkState;
import static com.google.android.exoplayer2.util.Util.msToUs;
import static com.google.android.exoplayer2.util.Util.secToUs;
import static com.google.android.exoplayer2.util.Util.sum;
import static com.google.android.exoplayer2.util.Util.usToMs;
import static java.lang.Math.min;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.util.Pair;
import android.view.ViewGroup;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import com.google.ads.interactivemedia.v3.api.Ad;
import com.google.ads.interactivemedia.v3.api.AdDisplayContainer;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent.AdErrorListener;
import com.google.ads.interactivemedia.v3.api.AdEvent;
import com.google.ads.interactivemedia.v3.api.AdEvent.AdEventListener;
import com.google.ads.interactivemedia.v3.api.AdPodInfo;
import com.google.ads.interactivemedia.v3.api.AdsLoader.AdsLoadedListener;
import com.google.ads.interactivemedia.v3.api.AdsManager;
import com.google.ads.interactivemedia.v3.api.AdsManagerLoadedEvent;
import com.google.ads.interactivemedia.v3.api.AdsRenderingSettings;
import com.google.ads.interactivemedia.v3.api.CompanionAdSlot;
import com.google.ads.interactivemedia.v3.api.CuePoint;
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory;
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings;
import com.google.ads.interactivemedia.v3.api.StreamDisplayContainer;
import com.google.ads.interactivemedia.v3.api.StreamManager;
import com.google.ads.interactivemedia.v3.api.StreamRequest;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;
import com.google.ads.interactivemedia.v3.api.player.VideoStreamPlayer;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.drm.DrmSessionManagerProvider;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.emsg.EventMessage;
import com.google.android.exoplayer2.metadata.id3.TextInformationFrame;
import com.google.android.exoplayer2.source.CompositeMediaSource;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.ForwardingTimeline;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ads.AdPlaybackState;
import com.google.android.exoplayer2.source.ads.ServerSideAdInsertionMediaSource;
import com.google.android.exoplayer2.source.ads.ServerSideAdInsertionMediaSource.AdPlaybackStateUpdater;
import com.google.android.exoplayer2.source.ads.ServerSideAdInsertionUtil;
import com.google.android.exoplayer2.ui.AdOverlayInfo;
import com.google.android.exoplayer2.ui.AdViewProvider;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.Loader.LoadErrorAction;
import com.google.android.exoplayer2.upstream.Loader.Loadable;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.ConditionVariable;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * MediaSource for IMA server side inserted ad streams.
 *
 * <p>TODO(bachinger) add code snippet from PlayerActivity
 */
public final class ImaServerSideAdInsertionMediaSource extends CompositeMediaSource<Void> {

  /**
   * Factory for creating {@link ImaServerSideAdInsertionMediaSource
   * ImaServerSideAdInsertionMediaSources}.
   *
   * <p>Apps can use the {@link ImaServerSideAdInsertionMediaSource.Factory} to customized the
   * {@link DefaultMediaSourceFactory} that is used to build a player:
   *
   * <p>TODO(bachinger) add code snippet from PlayerActivity
   */
  public static final class Factory implements MediaSource.Factory {

    private final AdsLoader adsLoader;
    private final MediaSource.Factory contentMediaSourceFactory;

    /**
     * Creates a new factory for {@link ImaServerSideAdInsertionMediaSource
     * ImaServerSideAdInsertionMediaSources}.
     *
     * @param adsLoader The {@link AdsLoader}.
     * @param contentMediaSourceFactory The content media source factory to create content sources.
     */
    public Factory(AdsLoader adsLoader, MediaSource.Factory contentMediaSourceFactory) {
      this.adsLoader = adsLoader;
      this.contentMediaSourceFactory = contentMediaSourceFactory;
    }

    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(
        @Nullable LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
      contentMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
      return this;
    }

    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(
        @Nullable DrmSessionManagerProvider drmSessionManagerProvider) {
      contentMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider);
      return this;
    }

    @Override
    public int[] getSupportedTypes() {
      return contentMediaSourceFactory.getSupportedTypes();
    }

    @Override
    public MediaSource createMediaSource(MediaItem mediaItem) {
      checkNotNull(mediaItem.localConfiguration);
      Player player = checkNotNull(adsLoader.player);
      StreamPlayer streamPlayer = new StreamPlayer(player, mediaItem);
      ImaSdkFactory imaSdkFactory = ImaSdkFactory.getInstance();
      StreamDisplayContainer streamDisplayContainer =
          createStreamDisplayContainer(imaSdkFactory, adsLoader.configuration, streamPlayer);
      com.google.ads.interactivemedia.v3.api.AdsLoader imaAdsLoader =
          imaSdkFactory.createAdsLoader(
              adsLoader.context, adsLoader.configuration.imaSdkSettings, streamDisplayContainer);
      ImaServerSideAdInsertionMediaSource mediaSource =
          new ImaServerSideAdInsertionMediaSource(
              mediaItem,
              player,
              adsLoader,
              imaAdsLoader,
              streamPlayer,
              contentMediaSourceFactory,
              adsLoader.configuration.applicationAdEventListener,
              adsLoader.configuration.applicationAdErrorListener);
      adsLoader.addMediaSourceResources(mediaSource, streamPlayer, imaAdsLoader);
      return mediaSource;
    }
  }

  /** An ads loader for IMA server side ad insertion streams. */
  public static final class AdsLoader {

    /** Builder for building an {@link AdsLoader}. */
    public static final class Builder {

      private final Context context;
      private final AdViewProvider adViewProvider;

      @Nullable private ImaSdkSettings imaSdkSettings;
      @Nullable private AdEventListener adEventListener;
      @Nullable private AdErrorEvent.AdErrorListener adErrorListener;
      private State state;
      private ImmutableList<CompanionAdSlot> companionAdSlots;

      /**
       * Creates an instance.
       *
       * @param context A context.
       * @param adViewProvider A provider for {@link ViewGroup} instances.
       */
      public Builder(Context context, AdViewProvider adViewProvider) {
        this.context = context;
        this.adViewProvider = adViewProvider;
        companionAdSlots = ImmutableList.of();
        state = new State(ImmutableMap.of());
      }

      /**
       * Sets the IMA SDK settings.
       *
       * <p>If this method is not called the default settings will be used.
       *
       * @param imaSdkSettings The {@link ImaSdkSettings}.
       * @return This builder, for convenience.
       */
      public AdsLoader.Builder setImaSdkSettings(ImaSdkSettings imaSdkSettings) {
        this.imaSdkSettings = imaSdkSettings;
        return this;
      }

      /**
       * Sets the optional {@link AdEventListener} that will be passed to {@link
       * AdsManager#addAdEventListener(AdEventListener)}.
       *
       * @param adEventListener The ad event listener.
       * @return This builder, for convenience.
       */
      public AdsLoader.Builder setAdEventListener(AdEventListener adEventListener) {
        this.adEventListener = adEventListener;
        return this;
      }

      /**
       * Sets the optional {@link AdErrorEvent.AdErrorListener} that will be passed to {@link
       * AdsManager#addAdErrorListener(AdErrorEvent.AdErrorListener)}.
       *
       * @param adErrorListener The {@link AdErrorEvent.AdErrorListener}.
       * @return This builder, for convenience.
       */
      public AdsLoader.Builder setAdErrorListener(AdErrorEvent.AdErrorListener adErrorListener) {
        this.adErrorListener = adErrorListener;
        return this;
      }

      /**
       * Sets the slots to use for companion ads, if they are present in the loaded ad.
       *
       * @param companionAdSlots The slots to use for companion ads.
       * @return This builder, for convenience.
       * @see AdDisplayContainer#setCompanionSlots(Collection)
       */
      public AdsLoader.Builder setCompanionAdSlots(Collection<CompanionAdSlot> companionAdSlots) {
        this.companionAdSlots = ImmutableList.copyOf(companionAdSlots);
        return this;
      }

      /**
       * Sets the optional state to resume with.
       *
       * <p>The state can be received when {@link #release() releasing} the {@link AdsLoader}.
       *
       * @param state The state to resume with.
       * @return This builder, for convenience.
       */
      public AdsLoader.Builder setAdsLoaderState(State state) {
        this.state = state;
        return this;
      }

      /** Returns a new {@link AdsLoader}. */
      public AdsLoader build() {
        @Nullable ImaSdkSettings imaSdkSettings = this.imaSdkSettings;
        if (imaSdkSettings == null) {
          imaSdkSettings = ImaSdkFactory.getInstance().createImaSdkSettings();
          imaSdkSettings.setLanguage(Util.getSystemLanguageCodes()[0]);
        }
        ImaUtil.ServerSideAdInsertionConfiguration configuration =
            new ImaUtil.ServerSideAdInsertionConfiguration(
                adViewProvider,
                imaSdkSettings,
                adEventListener,
                adErrorListener,
                companionAdSlots,
                imaSdkSettings.isDebugMode());
        return new AdsLoader(context, configuration, state);
      }
    }

    /** The state of the {@link AdsLoader}. */
    public static class State {

      private final ImmutableMap<String, AdPlaybackState> adPlaybackStates;

      private State(ImmutableMap<String, AdPlaybackState> adPlaybackStates) {
        this.adPlaybackStates = adPlaybackStates;
      }
    }

    private final ImaUtil.ServerSideAdInsertionConfiguration configuration;
    private final Context context;
    private final Map<ImaServerSideAdInsertionMediaSource, MediaSourceResourceHolder>
        mediaSourceResources;
    private final Map<String, AdPlaybackState> adPlaybackStateMap;

    @Nullable private Player player;

    private AdsLoader(
        Context context, ImaUtil.ServerSideAdInsertionConfiguration configuration, State state) {
      this.context = context.getApplicationContext();
      this.configuration = configuration;
      mediaSourceResources = new HashMap<>();
      adPlaybackStateMap = new HashMap<>();
      for (Map.Entry<String, AdPlaybackState> entry : state.adPlaybackStates.entrySet()) {
        adPlaybackStateMap.put(entry.getKey(), entry.getValue());
      }
    }

    /**
     * Sets the player.
     *
     * <p>This method needs to be called before adding server side ad insertion media items to the
     * player.
     */
    public void setPlayer(Player player) {
      this.player = player;
    }

    /**
     * Releases resources.
     *
     * @return The {@link State} that can be used to resume with.
     */
    public State release() {
      for (MediaSourceResourceHolder resourceHolder : mediaSourceResources.values()) {
        resourceHolder.streamPlayer.release();
        resourceHolder.adsLoader.release();
        resourceHolder.imaServerSideAdInsertionMediaSource.setStreamManager(
            /* streamManager= */ null);
      }
      mediaSourceResources.clear();
      player = null;
      return new State(ImmutableMap.copyOf(adPlaybackStateMap));
    }

    // Internal methods.

    private void addMediaSourceResources(
        ImaServerSideAdInsertionMediaSource mediaSource,
        StreamPlayer streamPlayer,
        com.google.ads.interactivemedia.v3.api.AdsLoader adsLoader) {
      mediaSourceResources.put(
          mediaSource, new MediaSourceResourceHolder(mediaSource, streamPlayer, adsLoader));
    }

    private AdPlaybackState getAdPlaybackState(String adsId) {
      @Nullable AdPlaybackState adPlaybackState = adPlaybackStateMap.get(adsId);
      return adPlaybackState != null ? adPlaybackState : AdPlaybackState.NONE;
    }

    private void setAdPlaybackState(String adsId, AdPlaybackState adPlaybackState) {
      this.adPlaybackStateMap.put(adsId, adPlaybackState);
    }

    private static final class MediaSourceResourceHolder {
      public final ImaServerSideAdInsertionMediaSource imaServerSideAdInsertionMediaSource;
      public final StreamPlayer streamPlayer;
      public final com.google.ads.interactivemedia.v3.api.AdsLoader adsLoader;

      private MediaSourceResourceHolder(
          ImaServerSideAdInsertionMediaSource imaServerSideAdInsertionMediaSource,
          StreamPlayer streamPlayer,
          com.google.ads.interactivemedia.v3.api.AdsLoader adsLoader) {
        this.imaServerSideAdInsertionMediaSource = imaServerSideAdInsertionMediaSource;
        this.streamPlayer = streamPlayer;
        this.adsLoader = adsLoader;
      }
    }
  }

  private final MediaItem mediaItem;
  private final Player player;
  private final MediaSource.Factory contentMediaSourceFactory;
  private final AdsLoader adsLoader;
  private final com.google.ads.interactivemedia.v3.api.AdsLoader sdkAdsLoader;
  @Nullable private final AdEventListener applicationAdEventListener;
  @Nullable private final AdErrorListener applicationAdErrorListener;
  private final boolean isLiveStream;
  private final String adsId;
  private final StreamRequest streamRequest;
  private final int loadVideoTimeoutMs;
  private final StreamPlayer streamPlayer;
  private final Handler mainHandler;
  private final ComponentListener componentListener;

  @Nullable private Loader loader;
  @Nullable private StreamManager streamManager;
  @Nullable private ServerSideAdInsertionMediaSource serverSideAdInsertionMediaSource;
  @Nullable private IOException loadError;
  private @MonotonicNonNull Timeline contentTimeline;
  private AdPlaybackState adPlaybackState;

  private ImaServerSideAdInsertionMediaSource(
      MediaItem mediaItem,
      Player player,
      AdsLoader adsLoader,
      com.google.ads.interactivemedia.v3.api.AdsLoader sdkAdsLoader,
      StreamPlayer streamPlayer,
      MediaSource.Factory contentMediaSourceFactory,
      @Nullable AdEventListener applicationAdEventListener,
      @Nullable AdErrorEvent.AdErrorListener applicationAdErrorListener) {
    this.mediaItem = mediaItem;
    this.player = player;
    this.adsLoader = adsLoader;
    this.sdkAdsLoader = sdkAdsLoader;
    this.streamPlayer = streamPlayer;
    this.contentMediaSourceFactory = contentMediaSourceFactory;
    this.applicationAdEventListener = applicationAdEventListener;
    this.applicationAdErrorListener = applicationAdErrorListener;
    componentListener = new ComponentListener();
    mainHandler = Util.createHandlerForCurrentLooper();
    Uri streamRequestUri = checkNotNull(mediaItem.localConfiguration).uri;
    isLiveStream = ImaServerSideAdInsertionUriBuilder.isLiveStream(streamRequestUri);
    adsId = ImaServerSideAdInsertionUriBuilder.getAdsId(streamRequestUri);
    loadVideoTimeoutMs = ImaServerSideAdInsertionUriBuilder.getLoadVideoTimeoutMs(streamRequestUri);
    streamRequest = ImaServerSideAdInsertionUriBuilder.createStreamRequest(streamRequestUri);
    adPlaybackState = adsLoader.getAdPlaybackState(adsId);
  }

  @Override
  public MediaItem getMediaItem() {
    return mediaItem;
  }

  @Override
  public void prepareSourceInternal(@Nullable TransferListener mediaTransferListener) {
    mainHandler.post(() -> assertSingleInstanceInPlaylist(checkNotNull(player)));
    super.prepareSourceInternal(mediaTransferListener);
    if (loader == null) {
      Loader loader = new Loader("ImaServerSideAdInsertionMediaSource");
      player.addListener(componentListener);
      StreamManagerLoadable streamManagerLoadable =
          new StreamManagerLoadable(
              sdkAdsLoader,
              streamRequest,
              streamPlayer,
              applicationAdErrorListener,
              loadVideoTimeoutMs);
      loader.startLoading(
          streamManagerLoadable,
          new StreamManagerLoadableCallback(),
          /* defaultMinRetryCount= */ 0);
      this.loader = loader;
    }
  }

  @Override
  protected void onChildSourceInfoRefreshed(
      Void id, MediaSource mediaSource, Timeline newTimeline) {
    refreshSourceInfo(
        new ForwardingTimeline(newTimeline) {
          @Override
          public Window getWindow(
              int windowIndex, Window window, long defaultPositionProjectionUs) {
            newTimeline.getWindow(windowIndex, window, defaultPositionProjectionUs);
            window.mediaItem = mediaItem;
            return window;
          }
        });
  }

  @Override
  public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
    return checkNotNull(serverSideAdInsertionMediaSource)
        .createPeriod(id, allocator, startPositionUs);
  }

  @Override
  public void releasePeriod(MediaPeriod mediaPeriod) {
    checkNotNull(serverSideAdInsertionMediaSource).releasePeriod(mediaPeriod);
  }

  @Override
  public void maybeThrowSourceInfoRefreshError() throws IOException {
    super.maybeThrowSourceInfoRefreshError();
    if (loadError != null) {
      IOException loadError = this.loadError;
      this.loadError = null;
      throw loadError;
    }
  }

  @Override
  protected void releaseSourceInternal() {
    super.releaseSourceInternal();
    if (loader != null) {
      loader.release();
      player.removeListener(componentListener);
      mainHandler.post(() -> setStreamManager(/* streamManager= */ null));
      loader = null;
    }
  }

  // Internal methods (called on the main thread).

  @MainThread
  private void setStreamManager(@Nullable StreamManager streamManager) {
    if (this.streamManager == streamManager) {
      return;
    }
    if (this.streamManager != null) {
      if (applicationAdEventListener != null) {
        this.streamManager.removeAdEventListener(applicationAdEventListener);
      }
      if (applicationAdErrorListener != null) {
        this.streamManager.removeAdErrorListener(applicationAdErrorListener);
      }
      this.streamManager.removeAdEventListener(componentListener);
      this.streamManager.destroy();
      this.streamManager = null;
    }
    this.streamManager = streamManager;
    if (streamManager != null) {
      streamManager.addAdEventListener(componentListener);
      if (applicationAdEventListener != null) {
        streamManager.addAdEventListener(applicationAdEventListener);
      }
      if (applicationAdErrorListener != null) {
        streamManager.addAdErrorListener(applicationAdErrorListener);
      }
    }
  }

  @MainThread
  private void setAdPlaybackState(AdPlaybackState adPlaybackState) {
    if (adPlaybackState.equals(this.adPlaybackState)) {
      return;
    }
    this.adPlaybackState = adPlaybackState;
    invalidateServerSideAdInsertionAdPlaybackState();
  }

  @MainThread
  @EnsuresNonNull("contentTimeline")
  private void setContentTimeline(Timeline contentTimeline) {
    if (contentTimeline.equals(this.contentTimeline)) {
      return;
    }
    this.contentTimeline = contentTimeline;
    invalidateServerSideAdInsertionAdPlaybackState();
  }

  @MainThread
  private void invalidateServerSideAdInsertionAdPlaybackState() {
    if (!adPlaybackState.equals(AdPlaybackState.NONE) && contentTimeline != null) {
      ImmutableMap<Object, AdPlaybackState> splitAdPlaybackStates =
          splitAdPlaybackStateForPeriods(adPlaybackState, contentTimeline);
      streamPlayer.setAdPlaybackStates(adsId, splitAdPlaybackStates, contentTimeline);
      checkNotNull(serverSideAdInsertionMediaSource).setAdPlaybackStates(splitAdPlaybackStates);
      if (!ImaServerSideAdInsertionUriBuilder.isLiveStream(
          checkNotNull(mediaItem.localConfiguration).uri)) {
        adsLoader.setAdPlaybackState(adsId, adPlaybackState);
      }
    }
  }

  // Internal methods (called on the playback thread).

  private void setContentUri(Uri contentUri) {
    if (serverSideAdInsertionMediaSource != null) {
      return;
    }
    MediaItem contentMediaItem =
        new MediaItem.Builder()
            .setUri(contentUri)
            .setDrmConfiguration(checkNotNull(mediaItem.localConfiguration).drmConfiguration)
            .setLiveConfiguration(mediaItem.liveConfiguration)
            .setCustomCacheKey(mediaItem.localConfiguration.customCacheKey)
            .setStreamKeys(mediaItem.localConfiguration.streamKeys)
            .build();
    ServerSideAdInsertionMediaSource serverSideAdInsertionMediaSource =
        new ServerSideAdInsertionMediaSource(
            contentMediaSourceFactory.createMediaSource(contentMediaItem), componentListener);
    this.serverSideAdInsertionMediaSource = serverSideAdInsertionMediaSource;
    if (isLiveStream) {
      AdPlaybackState liveAdPlaybackState =
          new AdPlaybackState(adsId)
              .withNewAdGroup(/* adGroupIndex= */ 0, /* adGroupTimeUs= */ C.TIME_END_OF_SOURCE)
              .withIsServerSideInserted(/* adGroupIndex= */ 0, true);
      mainHandler.post(() -> setAdPlaybackState(liveAdPlaybackState));
    }
    prepareChildSource(/* id= */ null, serverSideAdInsertionMediaSource);
  }

  // Static methods.

  private static AdPlaybackState setVodAdGroupPlaceholders(
      List<CuePoint> cuePoints, AdPlaybackState adPlaybackState) {
    for (int i = 0; i < cuePoints.size(); i++) {
      CuePoint cuePoint = cuePoints.get(i);
      adPlaybackState =
          addAdGroupToAdPlaybackState(
              adPlaybackState,
              /* fromPositionUs= */ secToUs(cuePoint.getStartTime()),
              /* contentResumeOffsetUs= */ 0,
              // TODO(b/192231683) Use getEndTimeMs()/getStartTimeMs() after jar target was removed
              /* adDurationsUs...= */ secToUs(cuePoint.getEndTime() - cuePoint.getStartTime()));
    }
    return adPlaybackState;
  }

  private static AdPlaybackState setVodAdInPlaceholder(Ad ad, AdPlaybackState adPlaybackState) {
    AdPodInfo adPodInfo = ad.getAdPodInfo();
    // Handle post rolls that have a podIndex of -1.
    int adGroupIndex =
        adPodInfo.getPodIndex() == -1 ? adPlaybackState.adGroupCount - 1 : adPodInfo.getPodIndex();
    AdPlaybackState.AdGroup adGroup = adPlaybackState.getAdGroup(adGroupIndex);
    int adIndexInAdGroup = adPodInfo.getAdPosition() - 1;
    if (adGroup.count < adPodInfo.getTotalAds()) {
      adPlaybackState =
          expandAdGroupPlaceholder(
              adGroupIndex,
              /* adGroupDurationUs= */ secToUs(adPodInfo.getMaxDuration()),
              adIndexInAdGroup,
              /* adDurationUs= */ secToUs(ad.getDuration()),
              /* adsInAdGroupCount= */ adPodInfo.getTotalAds(),
              adPlaybackState);
    } else if (adIndexInAdGroup < adGroup.count - 1) {
      adPlaybackState =
          updateAdDurationInAdGroup(
              adGroupIndex,
              adIndexInAdGroup,
              /* adDurationUs= */ secToUs(ad.getDuration()),
              adPlaybackState);
    }
    return adPlaybackState;
  }

  private static AdPlaybackState addLiveAdBreak(
      Ad ad, long currentPeriodPositionUs, AdPlaybackState adPlaybackState) {
    AdPodInfo adPodInfo = ad.getAdPodInfo();
    long adDurationUs = secToUs(ad.getDuration());
    int adIndexInAdGroup = adPodInfo.getAdPosition() - 1;

    // TODO(b/208398934) Support seeking backwards.
    if (adIndexInAdGroup == 0 || adPlaybackState.adGroupCount == 1) {
      // First ad of group. Create a new group with all ads.
      long[] adDurationsUs =
          updateAdDurationAndPropagate(
              new long[adPodInfo.getTotalAds()],
              adIndexInAdGroup,
              adDurationUs,
              secToUs(adPodInfo.getMaxDuration()));
      adPlaybackState =
          addAdGroupToAdPlaybackState(
              adPlaybackState,
              /* fromPositionUs= */ currentPeriodPositionUs,
              /* contentResumeOffsetUs= */ sum(adDurationsUs),
              /* adDurationsUs...= */ adDurationsUs);
    } else {
      int adGroupIndex = adPlaybackState.adGroupCount - 2;
      adPlaybackState =
          updateAdDurationInAdGroup(adGroupIndex, adIndexInAdGroup, adDurationUs, adPlaybackState);
      AdPlaybackState.AdGroup adGroup = adPlaybackState.getAdGroup(adGroupIndex);
      return adPlaybackState.withContentResumeOffsetUs(
          adGroupIndex, min(adGroup.contentResumeOffsetUs, sum(adGroup.durationsUs)));
    }
    return adPlaybackState;
  }

  private static AdPlaybackState skipAd(Ad ad, AdPlaybackState adPlaybackState) {
    AdPodInfo adPodInfo = ad.getAdPodInfo();
    int adGroupIndex = adPodInfo.getPodIndex();
    // IMA SDK always returns index starting at 1.
    int adIndexInAdGroup = adPodInfo.getAdPosition() - 1;
    return adPlaybackState.withSkippedAd(adGroupIndex, adIndexInAdGroup);
  }

  private final class ComponentListener
      implements AdEvent.AdEventListener, Player.Listener, AdPlaybackStateUpdater {

    // Implement Player.Listener.

    @Override
    public void onPositionDiscontinuity(
        Player.PositionInfo oldPosition,
        Player.PositionInfo newPosition,
        @Player.DiscontinuityReason int reason) {
      if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
        // Only auto transitions within the same or to the next media item are of interest.
        return;
      }

      if (mediaItem.equals(oldPosition.mediaItem) && !mediaItem.equals(newPosition.mediaItem)) {
        // Playback automatically transitioned to the next media item. Notify the SDK.
        streamPlayer.onContentCompleted();
      }

      if (!mediaItem.equals(oldPosition.mediaItem)
          || !mediaItem.equals(newPosition.mediaItem)
          || !adsId.equals(
              player
                  .getCurrentTimeline()
                  .getPeriodByUid(checkNotNull(newPosition.periodUid), new Timeline.Period())
                  .getAdsId())) {
        // Discontinuity not within this ad media source.
        return;
      }

      if (oldPosition.adGroupIndex != C.INDEX_UNSET) {
        int adGroupIndex = oldPosition.adGroupIndex;
        int adIndexInAdGroup = oldPosition.adIndexInAdGroup;
        Timeline timeline = player.getCurrentTimeline();
        Timeline.Window window =
            timeline.getWindow(oldPosition.mediaItemIndex, new Timeline.Window());
        if (window.lastPeriodIndex > window.firstPeriodIndex) {
          // Map adGroupIndex and adIndexInAdGroup to multi-period window.
          Pair<Integer, Integer> adGroupIndexAndAdIndexInAdGroup =
              getAdGroupAndIndexInMultiPeriodWindow(
                  oldPosition.periodIndex - window.firstPeriodIndex,
                  adPlaybackState,
                  checkNotNull(contentTimeline));
          adGroupIndex = adGroupIndexAndAdIndexInAdGroup.first;
          adIndexInAdGroup = adGroupIndexAndAdIndexInAdGroup.second;
        }
        int adState = adPlaybackState.getAdGroup(adGroupIndex).states[adIndexInAdGroup];
        if (adState == AdPlaybackState.AD_STATE_AVAILABLE
            || adState == AdPlaybackState.AD_STATE_UNAVAILABLE) {
          setAdPlaybackState(
              adPlaybackState.withPlayedAd(adGroupIndex, /* adIndexInAdGroup= */ adIndexInAdGroup));
        }
      }
    }

    @Override
    public void onMetadata(Metadata metadata) {
      if (!isCurrentAdPlaying(player, mediaItem, adsId)) {
        return;
      }
      for (int i = 0; i < metadata.length(); i++) {
        Metadata.Entry entry = metadata.get(i);
        if (entry instanceof TextInformationFrame) {
          TextInformationFrame textFrame = (TextInformationFrame) entry;
          if ("TXXX".equals(textFrame.id)) {
            streamPlayer.triggerUserTextReceived(textFrame.value);
          }
        } else if (entry instanceof EventMessage) {
          EventMessage eventMessage = (EventMessage) entry;
          String eventMessageValue = new String(eventMessage.messageData);
          streamPlayer.triggerUserTextReceived(eventMessageValue);
        }
      }
    }

    @Override
    public void onPlaybackStateChanged(@Player.State int state) {
      if (state == Player.STATE_ENDED && isCurrentAdPlaying(player, mediaItem, adsId)) {
        streamPlayer.onContentCompleted();
      }
    }

    @Override
    public void onVolumeChanged(float volume) {
      if (!isCurrentAdPlaying(player, mediaItem, adsId)) {
        return;
      }
      int volumePct = (int) Math.floor(volume * 100);
      streamPlayer.onContentVolumeChanged(volumePct);
    }

    // Implement AdEvent.AdEventListener.

    @MainThread
    @Override
    public void onAdEvent(AdEvent event) {
      AdPlaybackState newAdPlaybackState = adPlaybackState;
      switch (event.getType()) {
        case CUEPOINTS_CHANGED:
          // CUEPOINTS_CHANGED event is firing multiple times with the same queue points.
          if (!isLiveStream && newAdPlaybackState.equals(AdPlaybackState.NONE)) {
            newAdPlaybackState =
                setVodAdGroupPlaceholders(
                    checkNotNull(streamManager).getCuePoints(), new AdPlaybackState(adsId));
          }
          break;
        case LOADED:
          if (isLiveStream) {
            Timeline timeline = player.getCurrentTimeline();
            Timeline.Window window =
                timeline.getWindow(player.getCurrentMediaItemIndex(), new Timeline.Window());
            if (window.lastPeriodIndex > window.firstPeriodIndex) {
              // multi-period live not integrated
              return;
            }
            long positionInWindowUs =
                timeline.getPeriod(player.getCurrentPeriodIndex(), new Timeline.Period())
                    .positionInWindowUs;
            long currentPeriodPosition = msToUs(player.getCurrentPosition()) - positionInWindowUs;
            newAdPlaybackState =
                addLiveAdBreak(
                    event.getAd(),
                    currentPeriodPosition,
                    newAdPlaybackState.equals(AdPlaybackState.NONE)
                        ? new AdPlaybackState(adsId)
                        : newAdPlaybackState);
          } else {
            newAdPlaybackState = setVodAdInPlaceholder(event.getAd(), newAdPlaybackState);
          }
          break;
        case SKIPPED:
          if (!isLiveStream) {
            newAdPlaybackState = skipAd(event.getAd(), newAdPlaybackState);
          }
          break;
        default:
          // Do nothing.
          break;
      }
      setAdPlaybackState(newAdPlaybackState);
    }

    // Implement AdPlaybackStateUpdater (called on the playback thread).

    @Override
    public boolean onAdPlaybackStateUpdateRequested(Timeline contentTimeline) {
      mainHandler.post(() -> setContentTimeline(contentTimeline));
      // Defer source refresh to ad playback state update for VOD. Refresh immediately when live
      // with single period.
      return !isLiveStream || contentTimeline.getPeriodCount() > 1;
    }
  }

  private final class StreamManagerLoadableCallback
      implements Loader.Callback<StreamManagerLoadable> {

    @Override
    public void onLoadCompleted(
        StreamManagerLoadable loadable, long elapsedRealtimeMs, long loadDurationMs) {
      mainHandler.post(() -> setStreamManager(checkNotNull(loadable.getStreamManager())));
      setContentUri(checkNotNull(loadable.getContentUri()));
    }

    @Override
    public void onLoadCanceled(
        StreamManagerLoadable loadable,
        long elapsedRealtimeMs,
        long loadDurationMs,
        boolean released) {
      // We only cancel when the loader is released.
      checkState(released);
    }

    @Override
    public LoadErrorAction onLoadError(
        StreamManagerLoadable loadable,
        long elapsedRealtimeMs,
        long loadDurationMs,
        IOException error,
        int errorCount) {
      loadError = error;
      return Loader.DONT_RETRY;
    }
  }

  /** Loads the {@link StreamManager} and the content URI. */
  private static class StreamManagerLoadable
      implements Loadable, AdsLoadedListener, AdErrorListener {

    private final com.google.ads.interactivemedia.v3.api.AdsLoader adsLoader;
    private final StreamRequest request;
    private final StreamPlayer streamPlayer;
    @Nullable private final AdErrorListener adErrorListener;
    private final int loadVideoTimeoutMs;
    private final ConditionVariable conditionVariable;

    @Nullable private volatile StreamManager streamManager;
    @Nullable private volatile Uri contentUri;
    private volatile boolean cancelled;
    private volatile boolean error;
    @Nullable private volatile String errorMessage;
    private volatile int errorCode;

    /** Creates an instance. */
    private StreamManagerLoadable(
        com.google.ads.interactivemedia.v3.api.AdsLoader adsLoader,
        StreamRequest request,
        StreamPlayer streamPlayer,
        @Nullable AdErrorListener adErrorListener,
        int loadVideoTimeoutMs) {
      this.adsLoader = adsLoader;
      this.request = request;
      this.streamPlayer = streamPlayer;
      this.adErrorListener = adErrorListener;
      this.loadVideoTimeoutMs = loadVideoTimeoutMs;
      conditionVariable = new ConditionVariable();
      errorCode = -1;
    }

    /** Returns the DAI content URI or null if not yet available. */
    @Nullable
    public Uri getContentUri() {
      return contentUri;
    }

    /** Returns the stream manager or null if not yet loaded. */
    @Nullable
    public StreamManager getStreamManager() {
      return streamManager;
    }

    // Implement Loadable.

    @Override
    public void load() throws IOException {
      try {
        // SDK will call loadUrl on stream player for SDK once manifest uri is available.
        streamPlayer.setStreamLoadListener(
            (streamUri, subtitles) -> {
              contentUri = Uri.parse(streamUri);
              conditionVariable.open();
            });
        if (adErrorListener != null) {
          adsLoader.addAdErrorListener(adErrorListener);
        }
        adsLoader.addAdsLoadedListener(this);
        adsLoader.addAdErrorListener(this);
        adsLoader.requestStream(request);
        while (contentUri == null && !cancelled && !error) {
          try {
            conditionVariable.block();
          } catch (InterruptedException e) {
            /* Do nothing. */
          }
        }
        if (error && contentUri == null) {
          throw new IOException(errorMessage + " [errorCode: " + errorCode + "]");
        }
      } finally {
        adsLoader.removeAdsLoadedListener(this);
        adsLoader.removeAdErrorListener(this);
        if (adErrorListener != null) {
          adsLoader.removeAdErrorListener(adErrorListener);
        }
      }
    }

    @Override
    public void cancelLoad() {
      cancelled = true;
    }

    // AdsLoader.AdsLoadedListener implementation.

    @MainThread
    @Override
    public void onAdsManagerLoaded(AdsManagerLoadedEvent event) {
      StreamManager streamManager = event.getStreamManager();
      if (streamManager == null) {
        error = true;
        errorMessage = "streamManager is null after ads manager has been loaded";
        conditionVariable.open();
        return;
      }
      AdsRenderingSettings adsRenderingSettings =
          ImaSdkFactory.getInstance().createAdsRenderingSettings();
      adsRenderingSettings.setLoadVideoTimeout(loadVideoTimeoutMs);
      // After initialization completed the streamUri will be reported to the streamPlayer.
      streamManager.init(adsRenderingSettings);
      this.streamManager = streamManager;
    }

    // AdErrorEvent.AdErrorListener implementation.

    @MainThread
    @Override
    public void onAdError(AdErrorEvent adErrorEvent) {
      error = true;
      if (adErrorEvent.getError() != null) {
        @Nullable String errorMessage = adErrorEvent.getError().getMessage();
        if (errorMessage != null) {
          this.errorMessage = errorMessage.replace('\n', ' ');
        }
        errorCode = adErrorEvent.getError().getErrorCodeNumber();
      }
      conditionVariable.open();
    }
  }

  /**
   * Receives the content URI from the SDK and sends back in-band media metadata and playback
   * progression data to the SDK.
   */
  private static final class StreamPlayer implements VideoStreamPlayer {

    /** A listener to listen for the stream URI loaded by the SDK. */
    public interface StreamLoadListener {
      /**
       * Loads a stream with dynamic ad insertion given the stream url and subtitles array. The
       * subtitles array is only used in VOD streams.
       *
       * <p>Each entry in the subtitles array is a HashMap that corresponds to a language. Each map
       * will have a "language" key with a two letter language string value, a "language name" to
       * specify the set of subtitles if multiple sets exist for the same language, and one or more
       * subtitle key/value pairs. Here's an example the map for English:
       *
       * <p>"language" -> "en" "language_name" -> "English" "webvtt" ->
       * "https://example.com/vtt/en.vtt" "ttml" -> "https://example.com/ttml/en.ttml"
       */
      void onLoadStream(String streamUri, List<HashMap<String, String>> subtitles);
    }

    private final List<VideoStreamPlayer.VideoStreamPlayerCallback> callbacks;
    private final Player player;
    private final MediaItem mediaItem;
    private final Timeline.Window window;
    private final Timeline.Period period;

    private ImmutableMap<Object, AdPlaybackState> adPlaybackStates;
    @Nullable private Timeline contentTimeline;
    @Nullable private Object adsId;
    @Nullable private StreamLoadListener streamLoadListener;

    /** Creates an instance. */
    public StreamPlayer(Player player, MediaItem mediaItem) {
      this.player = player;
      this.mediaItem = mediaItem;
      callbacks = new ArrayList<>(/* initialCapacity= */ 1);
      adPlaybackStates = ImmutableMap.of();
      window = new Timeline.Window();
      period = new Timeline.Period();
    }

    /** Registers the ad playback states matching to the given content timeline. */
    public void setAdPlaybackStates(
        Object adsId,
        ImmutableMap<Object, AdPlaybackState> adPlaybackStates,
        Timeline contentTimeline) {
      this.adsId = adsId;
      this.adPlaybackStates = adPlaybackStates;
      this.contentTimeline = contentTimeline;
    }

    /** Sets the {@link StreamLoadListener} to be called when the SSAI content URI was loaded. */
    public void setStreamLoadListener(StreamLoadListener listener) {
      streamLoadListener = Assertions.checkNotNull(listener);
    }

    /** Called when the content has completed playback. */
    public void onContentCompleted() {
      for (VideoStreamPlayer.VideoStreamPlayerCallback callback : callbacks) {
        callback.onContentComplete();
      }
    }

    /** Called when the content player changed the volume. */
    public void onContentVolumeChanged(int volumePct) {
      for (VideoStreamPlayer.VideoStreamPlayerCallback callback : callbacks) {
        callback.onVolumeChanged(volumePct);
      }
    }

    /** Releases the player. */
    public void release() {
      callbacks.clear();
      adsId = null;
      adPlaybackStates = ImmutableMap.of();
      contentTimeline = null;
      streamLoadListener = null;
    }

    // Implements VolumeProvider.

    @Override
    public int getVolume() {
      return (int) Math.floor(player.getVolume() * 100);
    }

    // Implement ContentProgressProvider.

    @Override
    public VideoProgressUpdate getContentProgress() {
      if (!isCurrentAdPlaying(player, mediaItem, adsId)) {
        return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
      } else if (adPlaybackStates.isEmpty()) {
        return new VideoProgressUpdate(/* currentTimeMs= */ 0, /* durationMs= */ C.TIME_UNSET);
      }

      Timeline timeline = player.getCurrentTimeline();
      int currentPeriodIndex = player.getCurrentPeriodIndex();
      timeline.getPeriod(currentPeriodIndex, period, /* setIds= */ true);
      timeline.getWindow(player.getCurrentMediaItemIndex(), window);

      // We need the period of the content timeline because its period UIDs are the key used in the
      // ad playback state map. The period UIDs of the public timeline are different (masking).
      Timeline.Period contentPeriod =
          checkNotNull(contentTimeline)
              .getPeriod(
                  currentPeriodIndex - window.firstPeriodIndex,
                  new Timeline.Period(),
                  /* setIds= */ true);
      AdPlaybackState adPlaybackState = checkNotNull(adPlaybackStates.get(contentPeriod.uid));

      long streamPositionMs =
          usToMs(ServerSideAdInsertionUtil.getStreamPositionUs(player, adPlaybackState));
      if (window.windowStartTimeMs != C.TIME_UNSET) {
        // Add the time since epoch at start of the window for live streams.
        streamPositionMs += window.windowStartTimeMs + period.getPositionInWindowMs();
      } else if (currentPeriodIndex > window.firstPeriodIndex) {
        // Add the end position of the previous period in the underlying stream.
        checkNotNull(contentTimeline)
            .getPeriod(
                currentPeriodIndex - window.firstPeriodIndex - 1,
                contentPeriod,
                /* setIds= */ true);
        streamPositionMs += usToMs(contentPeriod.positionInWindowUs + contentPeriod.durationUs);
      }
      return new VideoProgressUpdate(
          streamPositionMs,
          checkNotNull(contentTimeline).getWindow(/* windowIndex= */ 0, window).getDurationMs());
    }

    // Implement VideoStreamPlayer.

    @Override
    public void loadUrl(String url, List<HashMap<String, String>> subtitles) {
      if (streamLoadListener != null) {
        // SDK provided manifest url, notify the listener.
        streamLoadListener.onLoadStream(url, subtitles);
      }
    }

    @Override
    public void addCallback(VideoStreamPlayer.VideoStreamPlayerCallback callback) {
      callbacks.add(callback);
    }

    @Override
    public void removeCallback(VideoStreamPlayer.VideoStreamPlayerCallback callback) {
      callbacks.remove(callback);
    }

    @Override
    public void onAdBreakStarted() {
      // Do nothing.
    }

    @Override
    public void onAdBreakEnded() {
      // Do nothing.
    }

    @Override
    public void onAdPeriodStarted() {
      // Do nothing.
    }

    @Override
    public void onAdPeriodEnded() {
      // Do nothing.
    }

    @Override
    public void pause() {
      // Do nothing.
    }

    @Override
    public void resume() {
      // Do nothing.
    }

    @Override
    public void seek(long timeMs) {
      // Do nothing.
    }

    // Internal methods.

    private void triggerUserTextReceived(String userText) {
      for (VideoStreamPlayer.VideoStreamPlayerCallback callback : callbacks) {
        callback.onUserTextReceived(userText);
      }
    }
  }

  private static boolean isCurrentAdPlaying(
      Player player, MediaItem mediaItem, @Nullable Object adsId) {
    if (player.getPlaybackState() == Player.STATE_IDLE) {
      return false;
    }
    Timeline.Period period = new Timeline.Period();
    player.getCurrentTimeline().getPeriod(player.getCurrentPeriodIndex(), period);
    return (period.isPlaceholder && mediaItem.equals(player.getCurrentMediaItem()))
        || (adsId != null && adsId.equals(period.getAdsId()));
  }

  private static StreamDisplayContainer createStreamDisplayContainer(
      ImaSdkFactory imaSdkFactory,
      ImaUtil.ServerSideAdInsertionConfiguration config,
      StreamPlayer streamPlayer) {
    StreamDisplayContainer container =
        ImaSdkFactory.createStreamDisplayContainer(
            checkNotNull(config.adViewProvider.getAdViewGroup()), streamPlayer);
    container.setCompanionSlots(config.companionAdSlots);
    registerFriendlyObstructions(imaSdkFactory, container, config.adViewProvider);
    return container;
  }

  private static void registerFriendlyObstructions(
      ImaSdkFactory imaSdkFactory,
      StreamDisplayContainer container,
      AdViewProvider adViewProvider) {
    for (int i = 0; i < adViewProvider.getAdOverlayInfos().size(); i++) {
      AdOverlayInfo overlayInfo = adViewProvider.getAdOverlayInfos().get(i);
      container.registerFriendlyObstruction(
          imaSdkFactory.createFriendlyObstruction(
              overlayInfo.view,
              ImaUtil.getFriendlyObstructionPurpose(overlayInfo.purpose),
              overlayInfo.reasonDetail != null ? overlayInfo.reasonDetail : "Unknown reason"));
    }
  }

  private static void assertSingleInstanceInPlaylist(Player player) {
    int counter = 0;
    for (int i = 0; i < player.getMediaItemCount(); i++) {
      MediaItem mediaItem = player.getMediaItemAt(i);
      if (mediaItem.localConfiguration != null
          && C.SSAI_SCHEME.equals(mediaItem.localConfiguration.uri.getScheme())
          && ImaServerSideAdInsertionUriBuilder.IMA_AUTHORITY.equals(
              mediaItem.localConfiguration.uri.getAuthority())) {
        if (++counter > 1) {
          throw new IllegalStateException(
              "Multiple IMA server side ad insertion sources not supported.");
        }
      }
    }
  }
}
