package org.schabi.newpipe.extractor.services.youtube.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.TimeAgoParser;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.extractCookieValue;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonResponse;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getResponse;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getUrlFromNavigationEndpoint;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.toJsonArray;

/**
 * A {@link YoutubePlaylistExtractor} for a mix (auto-generated playlist).
 * It handles URLs in the format of
 * {@code youtube.com/watch?v=videoId&list=playlistId}
 */
public class YoutubeMixPlaylistExtractor extends PlaylistExtractor {

    /**
     * YouTube identifies mixes based on this cookie. With this information it can generate
     * continuations without duplicates.
     */
    private static final String COOKIE_NAME = "VISITOR_INFO1_LIVE";

    private JsonObject initialData;
    private JsonObject playlistData;
    private String cookieValue;

    public YoutubeMixPlaylistExtractor(final StreamingService service,
                                       final ListLinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        final String url = getUrl() + "&pbj=1";
        final Response response = getResponse(url, getExtractorLocalization());
        final JsonArray ajaxJson = toJsonArray(response.responseBody());
        initialData = ajaxJson.getObject(3).getObject("response");
        playlistData = initialData.getObject("contents").getObject("twoColumnWatchNextResults")
                .getObject("playlist").getObject("playlist");
        cookieValue = extractCookieValue(COOKIE_NAME, response);
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        final String name = playlistData.getString("title");
        if (name == null) {
            throw new ParsingException("Could not get playlist name");
        }
        return name;
    }

    @Override
    public String getThumbnailUrl() throws ParsingException {
        try {
            return getThumbnailUrlFromPlaylistId(playlistData.getString("playlistId"));
        } catch (final Exception e) {
            try {
                //fallback to thumbnail of current video. Always the case for channel mix
                return getThumbnailUrlFromVideoId(
                    initialData.getObject("currentVideoEndpoint").getObject("watchEndpoint")
                        .getString("videoId"));
            } catch (final Exception ignored) {
            }
            throw new ParsingException("Could not get playlist thumbnail", e);
        }
    }

    @Override
    public String getBannerUrl() {
        return "";
    }

    @Override
    public String getUploaderUrl() {
        //Youtube mix are auto-generated
        return "";
    }

    @Override
    public String getUploaderName() {
        //Youtube mix are auto-generated by YouTube
        return "YouTube";
    }

    @Override
    public String getUploaderAvatarUrl() {
        //Youtube mix are auto-generated by YouTube
        return "";
    }

    @Override
    public long getStreamCount() {
        // Auto-generated playlist always start with 25 videos and are endless
        return ListExtractor.ITEM_COUNT_INFINITE;
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws ExtractionException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        collectStreamsFrom(collector, playlistData.getArray("contents"));
        return new InfoItemsPage<>(collector,
                new Page(getNextPageUrl(), Collections.singletonMap(COOKIE_NAME, cookieValue)));
    }

    private String getNextPageUrl() throws ExtractionException {
        return getNextPageUrlFrom(playlistData);
    }

    private String getNextPageUrlFrom(final JsonObject playlistJson) throws ExtractionException {
        final JsonObject lastStream = ((JsonObject) playlistJson.getArray("contents")
                .get(playlistJson.getArray("contents").size() - 1));
        if (lastStream == null || lastStream.getObject("playlistPanelVideoRenderer") == null) {
            throw new ExtractionException("Could not extract next page url");
        }

        return getUrlFromNavigationEndpoint(
                lastStream.getObject("playlistPanelVideoRenderer").getObject("navigationEndpoint"))
                + "&pbj=1";
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws ExtractionException, IOException {
        if (page == null || page.getUrl().isEmpty()) {
            throw new ExtractionException(
                new IllegalArgumentException("Page url is empty or null"));
        }

        final JsonArray ajaxJson = getJsonResponse(page, getExtractorLocalization());
        final JsonObject playlistJson =
                ajaxJson.getObject(3).getObject("response").getObject("contents")
                        .getObject("twoColumnWatchNextResults").getObject("playlist")
                        .getObject("playlist");
        final JsonArray allStreams = playlistJson.getArray("contents");
        // Sublist because youtube returns up to 24 previous streams in the mix
        // +1 because the stream of "currentIndex" was already extracted in previous request
        final List<Object> newStreams =
                allStreams.subList(playlistJson.getInt("currentIndex") + 1, allStreams.size());

        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        collectStreamsFrom(collector, newStreams);
        return new InfoItemsPage<>(collector,
                new Page(getNextPageUrlFrom(playlistJson), page.getCookies()));
    }

    private void collectStreamsFrom(
            @Nonnull final StreamInfoItemsCollector collector,
            @Nullable final List<Object> streams) {

        if (streams == null) {
            return;
        }

        final TimeAgoParser timeAgoParser = getTimeAgoParser();

        for (final Object stream : streams) {
            if (stream instanceof JsonObject) {
                final JsonObject streamInfo = ((JsonObject) stream)
                    .getObject("playlistPanelVideoRenderer");
                if (streamInfo != null) {
                    collector.commit(new YoutubeStreamInfoItemExtractor(streamInfo, timeAgoParser));
                }
            }
        }
    }

    private String getThumbnailUrlFromPlaylistId(final String playlistId) throws ParsingException {
        final String videoId;
        if (playlistId.startsWith("RDMM")) {
            videoId = playlistId.substring(4);
        } else if (playlistId.startsWith("RDCMUC")) {
            throw new ParsingException("is channel mix");
        } else {
            videoId = playlistId.substring(2);
        }
        if (videoId.isEmpty()) {
            throw new ParsingException("videoId is empty");
        }
        return getThumbnailUrlFromVideoId(videoId);
    }

    private String getThumbnailUrlFromVideoId(final String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    @Nonnull
    @Override
    public String getSubChannelName() {
        return "";
    }

    @Nonnull
    @Override
    public String getSubChannelUrl() {
        return "";
    }

    @Nonnull
    @Override
    public String getSubChannelAvatarUrl() {
        return "";
    }
}
