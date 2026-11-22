package com.newvowifi.ims.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** SoftVoWiFi v0.3.45 PCM stream, not RTP: BE32 total frame length followed by PCM16. */
public final class PcmFrameCodec {
  public static final int SAMPLE_RATE_HZ = 16000;
  public static final int CHANNEL_COUNT = 1;
  public static final int SAMPLES_PER_FRAME = 320;
  public static final int PAYLOAD_BYTES = 640;
  public static final int HEADER_BYTES = 4;
  public static final int FRAME_BYTES = 644;
  private PcmFrameCodec() {}

  public static byte[] encode(byte[] payload) {
    if (payload.length != PAYLOAD_BYTES)
      throw new IllegalArgumentException("PCM payload must be 640 bytes");
    byte[] frame = new byte[FRAME_BYTES];
    frame[0] = (byte) (FRAME_BYTES >>> 24);
    frame[1] = (byte) (FRAME_BYTES >>> 16);
    frame[2] = (byte) (FRAME_BYTES >>> 8);
    frame[3] = (byte) FRAME_BYTES;
    System.arraycopy(payload, 0, frame, HEADER_BYTES, PAYLOAD_BYTES);
    return frame;
  }

  public static final class Decoder {
    private final byte[] frame = new byte[FRAME_BYTES];
    private int used;
    private boolean failed;
    public List<byte[]> push(byte[] chunk) throws IOException {
      return push(chunk, 0, chunk.length);
    }
    public List<byte[]> push(byte[] chunk, int offset, int length) throws IOException {
      if (offset < 0 || length < 0 || offset > chunk.length - length)
        throw new IndexOutOfBoundsException();
      if (failed)
        throw new IOException("PCM decoder already failed");
      List<byte[]> result = new ArrayList<>();
      while (length > 0) {
        int boundary = used < HEADER_BYTES ? HEADER_BYTES : FRAME_BYTES;
        int copy = Math.min(length, boundary - used);
        System.arraycopy(chunk, offset, frame, used, copy);
        used += copy;
        offset += copy;
        length -= copy;
        if (used == HEADER_BYTES) {
          int total = ((frame[0] & 255) << 24) | ((frame[1] & 255) << 16) | ((frame[2] & 255) << 8)
              | (frame[3] & 255);
          if (total != FRAME_BYTES) {
            failed = true;
            throw new IOException("Unexpected PCM frame size");
          }
        }
        if (used == FRAME_BYTES) {
          result.add(Arrays.copyOfRange(frame, HEADER_BYTES, FRAME_BYTES));
          used = 0;
        }
      }
      return result;
    }
    public void finish() throws IOException {
      if (failed || used != 0)
        throw new IOException("Truncated or invalid PCM stream");
    }
  }
}
