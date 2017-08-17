/*
 * Copyright (C) 2013 Dmitry Polishuk dmitry.polishuk@gmail.com
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.example.sonic.mediaplayer;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by dp on 9/15/13.
 */
public class Player {

    private static final String TAG = "Player";

    // Media API provided by Android
    private MediaExtractor extractor;
    private MediaCodec videoDecoder;
    private MediaCodec audioDecoder;
    private Surface surface;

    // AudioTrack to play PCM data
    private AudioTrack audioTrack = null;

    private int audioIdx = -1, videoIdx = -1;

    long startMs = -1;

    private long audioPtsUs = -1;

    // Video and Audio Thread
    private Thread videoThread = null;
    private Thread audioThread = null;

    // Thread Control Variables
    private Object mPauseLock;
    private boolean mPaused;
    private boolean isEOS = false;
    private  boolean isRunning = false;

    // This is where your .mp4 file is located
//    private static final String SAMPLE = Environment.getExternalStorageDirectory() + "/YOUR_FOLDER/sample.mp4";
    private static final String SAMPLE = Environment.getExternalStorageDirectory() + "/sonictier/sample_3.mp4";

    public Player(Surface surface) throws IOException {
        this.surface = surface;

        extractor = new MediaExtractor();
        extractor.setDataSource(SAMPLE);

        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                videoIdx = i;
                extractor.selectTrack(i);
                videoDecoder = MediaCodec.createDecoderByType(mime);
                videoDecoder.configure(format, surface, null, 0);
                continue;
            }

            if (mime.startsWith("audio/")) {
                audioIdx = i;
                extractor.selectTrack(i);
                audioDecoder = MediaCodec.createDecoderByType(mime);
                audioDecoder.configure(format, null, null, 0);
                continue;
            }
        }

        if (null == videoDecoder) {
            throw new IOException("Cannot find video decoder");
        }

        if (null == audioDecoder) {
            throw new IOException("Cannot find audio decoder");
        }
    }

    public void start() {

        mPauseLock = new Object();
        isEOS = false;
        isRunning = false;
        mPaused = false;

        startMs = System.currentTimeMillis();

        videoThread = new VideoThread();
        audioThread = new AudioThread();

        videoThread.start();
        audioThread.start();
    }

    public void pauseToPlay() {
        synchronized (mPauseLock) {
            mPaused = false;
            mPauseLock.notifyAll();
        }
    }

    public void pause() {
        synchronized (mPauseLock) {
            mPaused = true;
        }
    }

    public void stop() {
        synchronized (mPauseLock) {
           isEOS = true;
           release();
        }
    }

    public void release() {
        if(videoThread!=null){
            videoDecoder.stop();
            videoDecoder.release();
            extractor.release();
        }
        if (audioThread!=null){
            audioDecoder.stop();
            audioDecoder.release();
            extractor.release();
        }
    }

    private double getAudioPtsMs() {
        synchronized (audioTrack) {
            if (audioTrack != null) {
                return (audioTrack.getPlaybackHeadPosition() / (double) audioTrack.getSampleRate()) * 1000.0;
            }
        }
        return 0;
    }

    private void processVideo(ByteBuffer[] videoInputBuffers, ByteBuffer[] videoOutputBuffers) {

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        if (!isEOS) {
            int inIndex = videoDecoder.dequeueInputBuffer(10000);

            synchronized (mPauseLock) {
                while (mPaused) {
                    try {
                        mPauseLock.wait();
                    } catch (InterruptedException e) {
                        e.getStackTrace();
                    }
                }

                if (inIndex >= 0) {
                    ByteBuffer buffer = videoInputBuffers[inIndex];
                    int videoSampleSize = extractor.readSampleData(buffer, 0);
                    if (videoSampleSize < 0) {
                        Log.d(TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                        videoDecoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isEOS = true;
                    } else {
                        videoDecoder.queueInputBuffer(inIndex, 0, videoSampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
        }

        int outIndex = videoDecoder.dequeueOutputBuffer(info, 10000);
        switch (outIndex) {
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                videoOutputBuffers = videoDecoder.getOutputBuffers();
                break;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                Log.d(TAG, "New format " + videoDecoder.getOutputFormat());
                break;
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                Log.d(TAG, "dequeueOutputBuffer timed out!");
                break;
            default:

                while (audioPtsUs > 0 && (info.presentationTimeUs / 1000.0 - getAudioPtsMs()) > 40.0) {
                    try {
                        Log.d(TAG, "Wait video " + info.presentationTimeUs / 1000.0 + " audio " + audioPtsUs / 1000.0);
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        break;
                    }
                }

                if ((info.presentationTimeUs / 1000.0 - getAudioPtsMs()) < -40.0) {
                    Log.d(TAG, "Need to SKIP Video frame video " + info.presentationTimeUs / 1000.0 + " audio " + getAudioPtsMs());
                }

                videoDecoder.releaseOutputBuffer(outIndex, true);
                break;
        }

        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
            return;
        }
    }

    private void processAudio(ByteBuffer[] audioInputBuffers, ByteBuffer[] audioOutputBuffers) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        if (!isEOS) {
            int inIndex = audioDecoder.dequeueInputBuffer(10000);

            synchronized (mPauseLock) {
                while (mPaused) {
                    try {
                        mPauseLock.wait();
                    } catch (InterruptedException e) {
                        e.getStackTrace();
                    }
                }

                if (inIndex >= 0) {
                    ByteBuffer buffer = audioInputBuffers[inIndex];
                    int audioSampleSize = extractor.readSampleData(buffer, 0);
                    if (audioSampleSize < 0) {
                        Log.d(TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                        audioDecoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isEOS = true;
                    } else {
                        audioDecoder.queueInputBuffer(inIndex, 0, audioSampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
        }

        int outIndex = audioDecoder.dequeueOutputBuffer(info, 10000);
        switch (outIndex) {
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                audioOutputBuffers = audioDecoder.getOutputBuffers();
                break;

            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                MediaFormat format = audioDecoder.getOutputFormat();
                Log.d(TAG, "New format " + format);

                int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

                int channelConfig = AudioFormat.CHANNEL_OUT_DEFAULT;
                switch (channelCount) {
                    case 1:
                        channelConfig = AudioFormat.CHANNEL_OUT_MONO;
                        break;
                    case 2:
                        channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                        break;
                    case 4:
                        channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                        break;
                    case 6:
                        channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                        break;
                    case 8:
                        channelConfig = AudioFormat.CHANNEL_OUT_7POINT1;
                }

                int bufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);

                audioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,
                        channelConfig,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize,
                        AudioTrack.MODE_STREAM);

                audioTrack.play();
                break;

            case MediaCodec.INFO_TRY_AGAIN_LATER:
                Log.d(TAG, "dequeueOutputBuffer timed out!");
                break;

            default:
                ByteBuffer buffer = audioOutputBuffers[outIndex];
                audioPtsUs = info.presentationTimeUs;

                byte[] pcm = new byte[info.size];
                buffer.get(pcm);
                buffer.clear();

                if (pcm.length > 0 && audioTrack != null) {
                    audioTrack.write(pcm, 0, pcm.length);
                }

                audioDecoder.releaseOutputBuffer(outIndex, false);
                break;
        }

        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
            return;
        }
    }

    /**
    * VideoThread
    * */
    private class VideoThread extends Thread {
        @Override
        public void run() {
            videoDecoder.start();

            ByteBuffer[] videoInputBuffers = videoDecoder.getInputBuffers();
            ByteBuffer[] videoOutputBuffers = videoDecoder.getOutputBuffers();

            while (!isRunning) {
                int currentTrackIdx = -1;
                synchronized (extractor) {
                    currentTrackIdx = extractor.getSampleTrackIndex();
                }

                //currentTrackIdx 가 1 에서 종료시에 -1 로 바뀜
                if (currentTrackIdx == -1) {
                    isRunning = true;
                }

                if (currentTrackIdx == videoIdx) {
                    processVideo(videoInputBuffers, videoOutputBuffers);
                }
            }

            Log.i(TAG, "Video Stopping......");

            release();
        }
    }

    /**
     * AudioThread
     * */
    private class AudioThread extends Thread {
        @Override
        public void run() {
            audioDecoder.start();

            ByteBuffer[] audioInputBuffers = audioDecoder.getInputBuffers();
            ByteBuffer[] audioOutputBuffers = audioDecoder.getOutputBuffers();

            while (!isRunning) {
                int currentTrackIdx = -1;
                synchronized (extractor) {
                    currentTrackIdx = extractor.getSampleTrackIndex();
                }

                //currentTrackIdx returns -1 when playback ends
                if (currentTrackIdx == -1) {
                    isRunning = true;
                }

                if (currentTrackIdx == audioIdx) {
                    processAudio(audioInputBuffers, audioOutputBuffers);
                }
            }

            Log.i(TAG, "Audio Stopping......");

            release();
        }
    }
}

