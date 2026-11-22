package com.newvowifi.ims;

import android.media.*;
import com.newvowifi.ims.engine.*;
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
  volatile boolean muted;
  PcmAudio(EngineSession e, String id, Executor io, Consumer<Throwable> failure) {
    engine = e;
    this.id = id;
    this.io = io;
    this.failure = failure;
  }
  void start() throws Exception {
    try {
      if (closed.get())
        return;
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
      r.startRecording();
      if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        throw new IOException("AUDIO_CAPTURE_UNAVAILABLE");
      t.play();
      thread("VoWiFi-PCM-Tx", () -> capture(s, r));
      thread("VoWiFi-PCM-Rx", () -> playback(s, t));
    } catch (Exception e) {
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
      if (muted)
        Arrays.fill(pcm, (byte) 0);
      out.write(PcmFrameCodec.encode(pcm));
      out.flush();
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
    }
  }
  private void fail(Throwable e) {
    if (!failed.compareAndSet(false, true))
      return;
    close();
    failure.accept(e);
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
    EngineRpcClient.Transport s;
    AudioRecord r;
    AudioTrack t;
    synchronized (resources) {
      s = transport;
      transport = null;
      r = record;
      record = null;
      t = track;
      track = null;
    }
    try {
      if (s != null)
        s.close();
    } catch (Exception ignored) {
    }
    if (r != null) {
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
    // Do not send stop ahead of a still-pending audioStart.
    startFinished.whenComplete((v, e) -> queueStop());
  }
}
