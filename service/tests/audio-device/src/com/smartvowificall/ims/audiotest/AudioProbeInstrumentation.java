package com.smartvowificall.ims.audiotest;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One bounded diagnostic capture in the existing target process. Never stores audio. */
public final class AudioProbeInstrumentation extends Instrumentation {
    private static final long CAPTURE_MS = 1000, CAPTURE_TIMEOUT_MS = 3000;
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicReference<Throwable> serviceLost = new AtomicReference<>();

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        result.putBoolean("instrumentation_affects_background_eligibility", true);
        result.putString("background_policy_verdict", "INCONCLUSIVE_INSTRUMENTATION_EXEMPTIONS");
        String stage = "preflight";
        Object lease = null;
        Class<?> policy = null;
        AudioRecord record = null;
        FutureTask<Bundle> capture = null;
        int outcome = Activity.RESULT_CANCELED;
        try {
            Context target = getTargetContext();
            require("com.smartvowificall.ims".equals(target.getPackageName()), "WRONG_TARGET_PACKAGE");
            require(Process.myUid() == target.getApplicationInfo().uid, "WRONG_TARGET_UID");
            result.putInt("process_uid", Process.myUid());
            ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(process);
            result.putInt("importance_before", process.importance);
            require(target.checkSelfPermission("android.permission.RECORD_AUDIO")
                    == PackageManager.PERMISSION_GRANTED, "RECORD_AUDIO_NOT_GRANTED");
            AudioManager audio = target.getSystemService(AudioManager.class);
            TelephonyManager phone = target.getSystemService(TelephonyManager.class);
            require(audio != null && phone != null, "SYSTEM_SERVICE_UNAVAILABLE");
            checkIdle(phone, audio);
            result.putInt("mode_before", audio.getMode());
            result.putInt("call_state_before", phone.getCallState());

            policy = Class.forName("com.smartvowificall.ims.PolicyService", false, target.getClassLoader());
            Field lockField = field(policy, "AUDIO_LOCK");
            Object lock = lockField.get(null);
            synchronized (lock) {
                require(field(policy, "running").get(null) != null, "POLICY_SERVICE_NOT_RUNNING_USE_NO_RESTART");
                require(((Set<?>) field(policy, "audioLeases").get(null)).isEmpty(), "EXISTING_AUDIO_LEASE");
            }
            stage = "foreground_acquire";
            Method acquire = policy.getDeclaredMethod("acquireAudio", Context.class, Consumer.class);
            acquire.setAccessible(true);
            lease = invoke(acquire, null, target, (Consumer<Throwable>) serviceLost::set);
            Method await = lease.getClass().getDeclaredMethod("awaitReady");
            await.setAccessible(true);
            stage = "foreground_wait";
            invoke(await, lease);
            result.putBoolean("foreground_ready", true);
            checkIdle(phone, audio);
            require(serviceLost.get() == null, "FOREGROUND_SERVICE_LOST");
            stage = "record_create";
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            require(minimum > 0, "AUDIO_FORMAT_UNAVAILABLE");
            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(2560, minimum)).build();
            require(record.getState() == AudioRecord.STATE_INITIALIZED, "AUDIO_RECORD_UNINITIALIZED");
            AudioRecord current = record;
            capture = new FutureTask<>(() -> measure(current, phone, audio));
            stage = "capture";
            daemon("VoWiFi-Audio-Probe", capture);
            result.putAll(capture.get(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            require(serviceLost.get() == null, "FOREGROUND_SERVICE_LOST");
            String verdict = result.getBoolean("policy_silenced_observed") ? "FAIL_POLICY_SILENCED"
                    : result.getLong("samples") == 0 ? "FAIL_NO_SAMPLES"
                    : !result.getBoolean("policy_state_known") ? "INCONCLUSIVE_POLICY_STATE_UNKNOWN"
                    : result.getLong("nonzero_samples") == 0 ? "INCONCLUSIVE_ZERO_INPUT"
                    : "PASS_CAPTURE_NONZERO";
            result.putString("capture_verdict", verdict);
            if (verdict.equals("PASS_CAPTURE_NONZERO")) outcome = Activity.RESULT_OK;
        } catch (Throwable failure) {
            Throwable cause = unwrap(failure);
            result.putString("failure_stage", stage);
            result.putString("failure_type", cause.getClass().getSimpleName());
            if (cause instanceof ProbeFailure) result.putString("failure_code", cause.getMessage());
            if (failure instanceof TimeoutException) result.putString("failure_code", "CAPTURE_DEADLINE_EXCEEDED");
            Throwable root = cause;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            result.putString("root_cause_type", root.getClass().getSimpleName());
        } finally {
            stop.set(true);
            if (capture != null) capture.cancel(true);
            if (record != null) {
                AudioRecord closing = record;
                FutureTask<Void> cleanup = new FutureTask<>(() -> {
                    try { closing.stop(); } catch (IllegalStateException ignored) {
                    } finally { closing.release(); }
                    return null;
                });
                daemon("VoWiFi-Audio-Probe-Release", cleanup);
                try {
                    cleanup.get(1, TimeUnit.SECONDS);
                    result.putBoolean("record_released", true);
                } catch (Exception e) {
                    result.putBoolean("record_released", false);
                    result.putString("cleanup_failure_type", unwrap(e).getClass().getSimpleName());
                    outcome = Activity.RESULT_CANCELED;
                }
            }
            if (lease != null) {
                try {
                    Method close = lease.getClass().getDeclaredMethod("close");
                    close.setAccessible(true);
                    invoke(close, lease);
                    CountDownLatch drained = new CountDownLatch(1);
                    new Handler(Looper.getMainLooper()).post(drained::countDown);
                    require(drained.await(1, TimeUnit.SECONDS), "FOREGROUND_RELEASE_MAIN_THREAD_TIMEOUT");
                    synchronized (field(policy, "AUDIO_LOCK").get(null)) {
                        require(!((Set<?>) field(policy, "audioLeases").get(null)).contains(lease),
                                "AUDIO_LEASE_NOT_RELEASED");
                    }
                    result.putBoolean("lease_released", true);
                } catch (Throwable e) {
                    result.putBoolean("lease_released", false);
                    result.putString("lease_cleanup_failure_type", unwrap(e).getClass().getSimpleName());
                    outcome = Activity.RESULT_CANCELED;
                }
            }
            result.putBoolean("pcm_stored", false);
            result.putString("stream", "Audio probe finished; instrumentation may grant background exemptions.\n");
            finish(outcome, result);
        }
    }

    private Bundle measure(AudioRecord record, TelephonyManager phone, AudioManager audio) throws Exception {
        Bundle result = new Bundle();
        byte[] buffer = new byte[640];
        long samples = 0, nonzero = 0;
        int peak = 0;
        boolean known = false, silenced = false;
        checkIdle(phone, audio);
        record.startRecording();
        int state = record.getRecordingState();
        require(state == AudioRecord.RECORDSTATE_RECORDING, "AUDIO_CAPTURE_NOT_RECORDING");
        long started = SystemClock.elapsedRealtime();
        while (!stop.get() && SystemClock.elapsedRealtime() - started < CAPTURE_MS) {
            checkIdle(phone, audio);
            require(serviceLost.get() == null, "FOREGROUND_SERVICE_LOST");
            int size = record.read(buffer, 0, buffer.length, AudioRecord.READ_NON_BLOCKING);
            require(size >= 0, "AUDIO_READ_FAILED");
            require((size & 1) == 0, "AUDIO_READ_PARTIAL_SAMPLE");
            for (int i = 0; i < size; i += 2) {
                int sample = (short) ((buffer[i] & 255) | (buffer[i + 1] << 8));
                samples++;
                if (sample != 0) nonzero++;
                peak = Math.max(peak, Math.abs(sample));
            }
            AudioRecordingConfiguration configuration = record.getActiveRecordingConfiguration();
            if (configuration != null) {
                known = true;
                silenced |= configuration.isClientSilenced();
            }
            if (size == 0) Thread.sleep(10);
        }
        require(!stop.get(), "CAPTURE_CANCELLED");
        result.putInt("recording_state", state);
        result.putBoolean("policy_state_known", known);
        result.putBoolean("policy_silenced_observed", silenced);
        result.putLong("samples", samples);
        result.putLong("nonzero_samples", nonzero);
        result.putInt("peak", peak);
        result.putLong("capture_elapsed_ms", SystemClock.elapsedRealtime() - started);
        result.putInt("mode_after", audio.getMode());
        return result;
    }

    private static void checkIdle(TelephonyManager phone, AudioManager audio) throws ProbeFailure {
        require(phone.getCallState() == TelephonyManager.CALL_STATE_IDLE, "CALL_NOT_IDLE");
        require(audio.getMode() == AudioManager.MODE_NORMAL, "AUDIO_MODE_NOT_NORMAL");
    }
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof InvocationTargetException
                || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        return failure;
    }
    private static void daemon(String name, Runnable work) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }
    private static void require(boolean condition, String code) throws ProbeFailure {
        if (!condition) throw new ProbeFailure(code);
    }
    private static final class ProbeFailure extends Exception {
        ProbeFailure(String code) { super(code); }
    }
}
