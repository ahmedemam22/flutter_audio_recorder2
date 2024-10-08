package com.example.flutter_audio_recorder2;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

import android.content.Context;

public class FlutterAudioRecorder2Plugin implements FlutterPlugin, MethodCallHandler {

    private static final String LOG_NAME = "AndroidAudioRecorder";
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 200;
    private static final byte RECORDER_BPP = 16; // we use 16bit
    private int mSampleRate = 16000; // 16Khz
    private AudioRecord mRecorder = null;
    private String mFilePath;
    private String mExtension;
    private int bufferSize = 1024;
    private FileOutputStream mFileOutputStream = null;
    private String mStatus = "unset";
    private double mPeakPower = -120;
    private double mAveragePower = -120;
    private Thread mRecordingThread = null;
    private long mDataSize = 0;
    private Result _result;
    private MethodChannel channel;
    private Context context;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "flutter_audio_recorder2");
        context = flutterPluginBinding.getApplicationContext();
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        _result = result;

        switch (call.method) {
            case "hasPermissions":
                handleHasPermission();
                break;
            case "init":
                handleInit(call, result);
                break;
            case "current":
                handleCurrent(call, result);
                break;
            case "start":
                handleStart(call, result);
                break;
            case "pause":
                handlePause(call, result);
                break;
            case "resume":
                handleResume(call, result);
                break;
            case "stop":
                handleStop(call, result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void handleHasPermission() {
        if (hasRecordPermission()) {
            Log.d(LOG_NAME, "handleHasPermission true");
            _result.success(true);
        } else {
            Log.d(LOG_NAME, "handleHasPermission false");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ActivityCompat.requestPermissions(null, new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST_RECORD_AUDIO);
            } else {
                ActivityCompat.requestPermissions(null, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
            }
        }
    }

    private boolean hasRecordPermission() {
        return (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                && (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
    }

    private void handleInit(MethodCall call, Result result) {
        resetRecorder();
        mSampleRate = Integer.parseInt(call.argument("sampleRate").toString());
        mFilePath = call.argument("path").toString();
        mExtension = call.argument("extension").toString();
        bufferSize = AudioRecord.getMinBufferSize(mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        mStatus = "initialized";
        HashMap<String, Object> initResult = new HashMap<>();
        initResult.put("duration", 0);
        initResult.put("path", mFilePath);
        initResult.put("audioFormat", mExtension);
        initResult.put("peakPower", mPeakPower);
        initResult.put("averagePower", mAveragePower);
        initResult.put("isMeteringEnabled", true);
        initResult.put("status", mStatus);
        result.success(initResult);
    }

    private void handleCurrent(MethodCall call, Result result) {
        HashMap<String, Object> currentResult = new HashMap<>();
        currentResult.put("duration", getDuration() * 1000);
        currentResult.put("path", (mStatus.equals("stopped")) ? mFilePath : getTempFilename());
        currentResult.put("audioFormat", mExtension);
        currentResult.put("peakPower", mPeakPower);
        currentResult.put("averagePower", mAveragePower);
        currentResult.put("isMeteringEnabled", true);
        currentResult.put("status", mStatus);
        result.success(currentResult);
    }

    private void handleStart(MethodCall call, Result result) {
        mRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        try {
            mFileOutputStream = new FileOutputStream(getTempFilename());
        } catch (IOException e) {
            result.error("", "cannot find the file", null);
            return;
        }
        mRecorder.startRecording();
        mStatus = "recording";
        startThread();
        result.success(null);
    }

    private void handlePause(MethodCall call, Result result) {
        mStatus = "paused";
        mPeakPower = -120;
        mAveragePower = -120;
        mRecorder.stop();
        mRecordingThread = null;
        result.success(null);
    }

    private void handleResume(MethodCall call, Result result) {
        mStatus = "recording";
        mRecorder.startRecording();
        startThread();
        result.success(null);
    }

    private void handleStop(MethodCall call, Result result) {
        if (mStatus.equals("stopped")) {
            result.success(null);
        } else {
            mStatus = "stopped";

            // Return Recording Object
            HashMap<String, Object> currentResult = new HashMap<>();
            currentResult.put("duration", getDuration() * 1000);
            currentResult.put("path", mFilePath);
            currentResult.put("audioFormat", mExtension);
            currentResult.put("peakPower", mPeakPower);
            currentResult.put("averagePower", mAveragePower);
            currentResult.put("isMeteringEnabled", true);
            currentResult.put("status", mStatus);

            resetRecorder();
            mRecordingThread = null;
            mRecorder.stop();
            mRecorder.release();
            try {
                mFileOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            Log.d(LOG_NAME, "before adding the wav header");
            copyWaveFile(getTempFilename(), mFilePath);
            deleteTempFile();

            result.success(currentResult);
        }
    }

    private void startThread() {
        mRecordingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                processAudioStream();
            }
        }, "Audio Processing Thread");
        mRecordingThread.start();
    }

    private void processAudioStream() {
        Log.d(LOG_NAME, "processing the stream: " + mStatus);
        byte[] bData = new byte[bufferSize];

        while (mStatus.equals("recording")) {
            Log.d(LOG_NAME, "reading audio data");
            mRecorder.read(bData, 0, bData.length);
            mDataSize += bData.length;
            updatePowers(bData);
            try {
                mFileOutputStream.write(bData);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void resetRecorder() {
        mPeakPower = -120;
        mAveragePower = -120;
        mDataSize = 0;
    }

    private void updatePowers(byte[] bData) {
        short[] data = byte2short(bData);
        short sampleVal = data[data.length - 1];
        String[] escapeStatusList = new String[]{"paused", "stopped", "initialized", "unset"};

        if (sampleVal == 0 || Arrays.asList(escapeStatusList).contains(mStatus)) {
            mAveragePower = -120;
        } else {
            double iOSFactor = 0.25;
            mAveragePower = 20 * Math.log(Math.abs(sampleVal) / 32768.0) * iOSFactor;
        }

        mPeakPower = mAveragePower;
    }

    private int getDuration() {
        return (int) (mDataSize / (mSampleRate * 2));
    }

    private String getTempFilename() {
        return mFilePath + ".temp";
    }

    private void copyWaveFile(String inFilename, String outFilename) {
        try (FileInputStream in = new FileInputStream
