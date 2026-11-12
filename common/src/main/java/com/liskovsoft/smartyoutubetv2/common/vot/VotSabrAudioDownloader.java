package com.liskovsoft.smartyoutubetv2.common.vot;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.DefaultExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.source.sabr.manifest.AdaptationSet;
import com.google.android.exoplayer2.source.sabr.manifest.Representation;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifest;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifestParser;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.parser.models.FormatSelector;
import com.google.android.exoplayer2.source.sabr.parser.models.SabrFormatMetadata;
import com.google.android.exoplayer2.source.sabr.parser.parts.MediaSegmentDataSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.MediaSegmentEndSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.RefreshPlayerResponseSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.SabrPart;
import com.google.android.exoplayer2.upstream.ByteArrayDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads a whole audio-only stream over SABR, for videos whose formats have no direct url.
 * Yandex asks for the original audio of videos it has never translated, and without it fails them.
 * Reuses the player's SABR parser; requests are made back to back instead of at playback speed.
 */
public final class VotSabrAudioDownloader {
    private static final String TAG = VotSabrAudioDownloader.class.getSimpleName();
    private static final MediaType PROTOBUF = MediaType.parse("application/x-protobuf");
    private static final int MAX_REQUESTS = 3000;
    private static final int MAX_EMPTY_RESPONSES = 5;
    private static final int MAX_BACKOFF_MS = 10_000;
    private static volatile OkHttpClient sClient;

    private VotSabrAudioDownloader() {
    }

    public static byte[] download(MediaItemFormatInfo info, MediaFormat audio) throws IOException {
        SabrManifest manifest = new SabrManifestParser().parse(info);
        Format format = findFormat(manifest, audio);
        if (format == null) {
            throw new IOException("SABR manifest has no audio itag " + audio.getITag());
        }
        SabrStream stream = manifest.getSabrStream(C.TRACK_TYPE_AUDIO);
        stream.setFormatSelector(new FormatSelector("selected_audio", false, format));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // a segment is kept only once its MEDIA_END arrives: a cut response must not leave half of it in the file
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        Set<Long> written = new HashSet<>();
        boolean initWritten = false;
        long totalSegments = -1;
        long lastSequence = 0;
        int emptyResponses = 0;

        for (int request = 0; request < MAX_REQUESTS; request++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("SABR audio download cancelled");
            }
            byte[] response = post(manifest.getRequestUrl(C.TRACK_TYPE_AUDIO),
                    manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_AUDIO, request == 0).toByteArray());
            ExtractorInput input = toExtractorInput(response);
            segment.reset();
            boolean newSegments = false;

            SabrPart part;
            while ((part = stream.parse(input)) != null) {
                if (part instanceof MediaSegmentDataSabrPart) {
                    MediaSegmentDataSabrPart data = (MediaSegmentDataSabrPart) part;
                    byte[] buffer = new byte[data.contentLength];
                    readFully(data.data, buffer);
                    segment.write(buffer);
                } else if (part instanceof MediaSegmentEndSabrPart) {
                    MediaSegmentEndSabrPart end = (MediaSegmentEndSabrPart) part;
                    if (end.isInitSegment ? !initWritten : written.add(end.sequenceNumber)) {
                        segment.writeTo(out);
                        initWritten |= end.isInitSegment;
                        if (!end.isInitSegment) {
                            newSegments = true;
                            lastSequence = Math.max(lastSequence, end.sequenceNumber);
                        }
                    }
                    segment.reset();
                    if (end.totalSegments > 0) {
                        totalSegments = end.totalSegments;
                    }
                } else if (part instanceof RefreshPlayerResponseSabrPart) {
                    throw new IOException("SABR asked to reload the player response");
                }
            }

            if (initWritten && totalSegments > 0 && lastSequence >= totalSegments) {
                Log.i(TAG, "SABR audio downloaded: itag=%s, segments=%d, bytes=%d, requests=%d",
                        audio.getITag(), totalSegments, out.size(), request + 1);
                return out.toByteArray();
            }

            emptyResponses = newSegments ? 0 : emptyResponses + 1;
            if (emptyResponses > MAX_EMPTY_RESPONSES) {
                throw new IOException("SABR stopped sending audio at segment " + lastSequence + "/" + totalSegments);
            }
            sleep(Math.min(stream.getBackoffTimeMs(), MAX_BACKOFF_MS));
        }

        throw new IOException("SABR audio needs more than " + MAX_REQUESTS + " requests");
    }

    @Nullable
    private static Format findFormat(SabrManifest manifest, MediaFormat audio) {
        for (int i = 0; i < manifest.getPeriodCount(); i++) {
            for (AdaptationSet set : manifest.getPeriod(i).adaptationSets) {
                if (set.type != C.TRACK_TYPE_AUDIO) {
                    continue;
                }
                for (Representation representation : set.representations) {
                    Format format = representation.format;
                    if (Helpers.equals(format.id, audio.getITag())
                            && Helpers.equals(audioTrackId(format), audio.getAudioTrackId())) {
                        return format;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static String audioTrackId(Format format) {
        Metadata metadata = format.metadata;
        for (int i = 0; metadata != null && i < metadata.length(); i++) {
            if (metadata.get(i) instanceof SabrFormatMetadata) {
                return ((SabrFormatMetadata) metadata.get(i)).audioTrackId;
            }
        }
        return null;
    }

    private static byte[] post(String url, byte[] body) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", DefaultHeaders.APP_USER_AGENT)
                .header("Accept", "application/vnd.yt-ump")
                .post(RequestBody.create(PROTOBUF, body))
                .build();
        try (Response response = getClient().newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful() || responseBody == null) {
                throw new IOException("SABR request failed: HTTP " + response.code());
            }
            return responseBody.bytes();
        }
    }

    private static ExtractorInput toExtractorInput(byte[] data) throws IOException {
        ByteArrayDataSource source = new ByteArrayDataSource(data);
        source.open(new DataSpec(Uri.EMPTY));
        return new DefaultExtractorInput(source, 0, data.length);
    }

    private static void readFully(ExtractorInput input, byte[] buffer) throws IOException {
        try {
            input.readFully(buffer, 0, buffer.length);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("SABR audio download cancelled");
        }
    }

    private static void sleep(int ms) throws InterruptedIOException {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("SABR audio download cancelled");
        }
    }

    private static OkHttpClient getClient() {
        if (sClient == null) {
            synchronized (VotSabrAudioDownloader.class) {
                if (sClient == null) {
                    sClient = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return sClient;
    }
}
