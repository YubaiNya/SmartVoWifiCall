package com.smartvowificall.ims;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.util.Log;
import com.smartvowificall.ims.engine.*;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One PCM attachment. Engine start/stop use I/O workers, never the framework state worker. */
final class PcmAudio implements AutoCloseable {
  final EngineSession engine;
  final String id;
  final Executor io;
  final Consumer<Throwable> failure;
  private final Object resources = new Object();
  private final AtomicBoolean closed = new AtomicBoolean(), failed = new AtomicBoolean(),
                              stopQueued = new AtomicBoolean();
  private final CompletableFuture<Void> startFinished = new CompletableFuture<>();
  private volatile boolean startAttempted;
  private EngineRpcClient.Transport transport;
  private AudioRecord record;
  private AudioTrack track;
  private AudioManager.AudioRecordingCallback recordingCallback;
  private PolicyService.AudioLease foreground;
  private final Context context;
  final CaptureLevels levels = new CaptureLevels();
  volatile boolean muted;
  PcmAudio(EngineSession e, String id, Executor io, Consumer<Throwable> failure) {
    this(PolicyService.audioContext(), e, id, io, failure);
  }
  PcmAudio(Context context, EngineSession e, String id, Executor io, Consumer<Throwable> failure) {
    this.context = context == null ? null : context.getApplicationContext();
    engine = e;
    this.id = id;
    this.io = io;
    this.failure = failure;
  }
  void start() throws Exception {
    try {
      if (closed.get())
        return;
      if (context == null) throw new IOException("AUDIO_CONTEXT_UNAVAILABLE");
      if (context.checkSelfPermission("android.permission.RECORD_AUDIO")
          != PackageManager.PERMISSION_GRANTED)
        throw new IOException("AUDIO_RECORD_PERMISSION_DENIED");
      PolicyService.AudioLease lease = PolicyService.acquireAudio(context, e -> {
        try {
          io.execute(() -> { if (!closed.get()) fail(e); });
        } catch (RejectedExecutionException ignored) { close(); }
      });
      synchronized (resources) {
        if (closed.get()) {
          lease.close();
          return;
        }
        foreground = lease;
      }
      lease.awaitReady();
      if (closed.get()) return;
      AudioManager manager = context.getSystemService(AudioManager.class);
      Log.i("VoWiFiAudio", "capture_ready mode=" + manager.getMode()
          + " source=VOICE_COMMUNICATION");
      EngineRpcClient.Transport s = engine.openAudioTransport(EngineSession.DEFAULT_TIMEOUT_MS);
      synchronized (resources) {
        if (closed.get()) {
          s.close();
          return;
        }
        transport = s;
      }
      AudioFormat input = new AudioFormat.Builder()
                              .setSampleRate(16000)
                              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                              .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                              .build();
      AudioRecord r = new AudioRecord.Builder()
                          .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                          .setAudioFormat(input)
                          .setBufferSizeInBytes(Math.max(2560,
                              AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                                  AudioFormat.ENCODING_PCM_16BIT)))
                          .build();
      synchronized (resources) {
        if (closed.get()) {
          r.release();
          return;
        }
        record = r;
      }
      AudioTrack t =
          new AudioTrack.Builder()
              .setAudioAttributes(new AudioAttributes.Builder()
                                      .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                      .build())
              .setAudioFormat(new AudioFormat.Builder()
                                  .setSampleRate(16000)
                                  .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                  .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                  .build())
              .setBufferSizeInBytes(Math.max(2560,
                  AudioTrack.getMinBufferSize(
                      16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build();
      synchronized (resources) {
        if (closed.get()) {
          t.release();
          return;
        }
        track = t;
      }
      if (r.getState() != AudioRecord.STATE_INITIALIZED
          || t.getState() != AudioTrack.STATE_INITIALIZED)
        throw new IOException("AUDIO_UNAVAILABLE");
      if (closed.get())
        return;
      startAttempted = true;
      engine.audioStart(id);
      if (closed.get())
        return;
      synchronized (resources) {
        if (closed.get()) return;
        recordingCallback = new AudioManager.AudioRecordingCallback() {
          @Override public void onRecordingConfigChanged(
              java.util.List<AudioRecordingConfiguration> configurations) {
            for (AudioRecordingConfiguration configuration : configurations)
              if (configuration.getClientAudioSessionId() == r.getAudioSessionId())
                recordingConfiguration(configuration);
          }
        };
        r.registerAudioRecordingCallback(io, recordingCallback);
      }
      r.startRecording();
      if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        throw new IOException("AUDIO_CAPTURE_UNAVAILABLE");
      recordingConfiguration(r.getActiveRecordingConfiguration());
      t.play();
      thread("VoWiFi-PCM-Tx", () -> capture(s, r));
      thread("VoWiFi-PCM-Rx", () -> playback(s, t));
    } catch (Exception e) {
      startFinished.complete(null);
      if (!closed.get())
        fail(e);
      throw e;
    } finally {
      startFinished.complete(null);
      if (closed.get())
        queueStop();
    }
  }
  interface Work {
    void run() throws Exception;
  }
  private void thread(String name, Work work) {
    Thread t = new Thread(() -> {
      try {
        work.run();
      } catch (Exception e) {
        if (!closed.get())
          fail(e);
      }
    }, name);
    t.setDaemon(true);
    t.start();
  }
  private void capture(EngineRpcClient.Transport s, AudioRecord r) throws Exception {
    DataOutputStream out = new DataOutputStream(s.output());
    byte[] pcm = new byte[640];
    while (!closed.get()) {
      int at = 0;
      while (!closed.get() && at < 640) {
        int n = r.read(pcm, at, 640 - at);
        if (n <= 0)
          throw new IOException("AUDIO_CAPTURE_FAILED");
        at += n;
      }
      if (closed.get())
        break;
      boolean appMuted = muted;
      levels.capture(pcm, appMuted);
      if (appMuted)
        Arrays.fill(pcm, (byte) 0);
      out.write(PcmFrameCodec.encode(pcm));
      out.flush();
      if (levels.transmitted() % 250 == 0) Log.i("VoWiFiAudio", levels.summary());
    }
  }
  private void playback(EngineRpcClient.Transport s, AudioTrack t) throws Exception {
    DataInputStream in = new DataInputStream(s.input());
    byte[] pcm = new byte[640];
    while (!closed.get()) {
      if (in.readInt() != PcmFrameCodec.FRAME_BYTES)
        throw new IOException("AUDIO_FRAME_INVALID");
      in.readFully(pcm);
      int at = 0;
      while (!closed.get() && at < 640) {
        int n = t.write(pcm, at, 640 - at);
        if (n <= 0)
          throw new IOException("AUDIO_PLAYBACK_FAILED");
        at += n;
      }
      if (!closed.get()) levels.playback();
    }
  }
  private void recordingConfiguration(AudioRecordingConfiguration configuration) {
    if (configuration == null || closed.get()) return;
    if (levels.silenced(configuration.isClientSilenced()))
      Log.i("VoWiFiAudio", levels.summary());
  }
  static final class CaptureLevels {
    private long capturedFrames, transmittedFrames, zeroFrames, mutedFrames, playbackFrames;
    private int peak, rms;
    private Boolean policySilenced;
    synchronized void capture(byte[] pcm, boolean muted) {
      long squares = 0;
      int framePeak = 0;
      for (int i = 0; i < pcm.length; i += 2) {
        int sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
        framePeak = Math.max(framePeak, Math.abs(sample));
        squares += (long) sample * sample;
      }
      capturedFrames++;
      if (framePeak == 0) zeroFrames++;
      if (muted) mutedFrames++;
      peak = framePeak;
      rms = (int) Math.sqrt(squares / (pcm.length / 2.0));
    }
    synchronized long transmitted() { return ++transmittedFrames; }
    synchronized void playback() { playbackFrames++; }
    synchronized boolean silenced(boolean value) {
      boolean changed = policySilenced == null || policySilenced != value;
      policySilenced = value;
      return changed;
    }
    synchronized String summary() {
      return "capture_frames=" + capturedFrames + " tx_frames=" + transmittedFrames
          + " rx_frames=" + playbackFrames + " zero_frames=" + zeroFrames
          + " muted_frames=" + mutedFrames + " peak=" + peak + " rms=" + rms
          + " policy_silenced=" + (policySilenced == null ? "unknown" : policySilenced);
    }
  }
  private void fail(Throwable e) {
    if (closed.get() || !failed.compareAndSet(false, true))
      return;
    Log.i("VoWiFiAudio", "capture_failed type=" + e.getClass().getSimpleName()
        + " cause=" + (e.getCause() == null ? "none" : e.getCause().getClass().getSimpleName()));
    // Report first: a blocked device/relay teardown must not keep Telecom in a live call.
    try {
      failure.accept(e);
    } finally {
      close();
    }
  }
  private void queueStop() {
    if (!startFinished.isDone() || !startAttempted || !stopQueued.compareAndSet(false, true))
      return;
    try {
      io.execute(() -> {
        try {
          engine.audioStop(id);
        } catch (Exception ignored) {
        }
      });
    } catch (RejectedExecutionException ignored) { /* Owning runtime has already shut down. */
    }
  }
  public void close() {
    if (!closed.compareAndSet(false, true))
      return;
    // Release the engine attachment even if a local stream/device close blocks.
    // A pending audioStart still establishes the required start-before-stop order.
    startFinished.whenComplete((v, e) -> queueStop());
    EngineRpcClient.Transport s;
    AudioRecord r;
    AudioTrack t;
    AudioManager.AudioRecordingCallback callback;
    PolicyService.AudioLease lease;
    synchronized (resources) {
      s = transport;
      transport = null;
      r = record;
      record = null;
      t = track;
      track = null;
      callback = recordingCallback;
      recordingCallback = null;
      lease = foreground;
      foreground = null;
    }
    if (r != null) {
      try {
        if (callback != null) r.unregisterAudioRecordingCallback(callback);
      } catch (Exception ignored) {
      }
      try {
        r.stop();
      } catch (Exception ignored) {
      }
      try {
        r.release();
      } catch (Exception ignored) {
      }
    }
    if (t != null) {
      try {
        t.stop();
      } catch (Exception ignored) {
      }
      try {
        t.release();
      } catch (Exception ignored) {
      }
    }
    if (lease != null) lease.close();
    try {
      if (s != null) s.close();
    } catch (Exception ignored) {
    }
    Log.i("VoWiFiAudio", "capture_closed " + levels.summary());
  }
}
