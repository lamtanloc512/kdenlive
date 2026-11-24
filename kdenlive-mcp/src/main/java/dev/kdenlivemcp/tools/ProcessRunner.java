package dev.kdenlivemcp.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs short-lived external commands (ffprobe, melt -query, frame grabs).
 * <p>
 * Never inherits stdin/stdout from this process: our stdout is the MCP channel and our stdin carries
 * requests. Child stdin is closed immediately; child stdout/stderr are captured (capped).
 */
public final class ProcessRunner {

    private static final int MAX_CAPTURE = 4 * 1024 * 1024;

    private ProcessRunner() {
    }

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }

        /** Last {@code n} lines of stderr, for error messages. */
        public String stderrTail(int n) {
            return tail(stderr, n);
        }
    }

    public static Result run(List<String> command, Duration timeout) {
        return run(command, timeout, Map.of(), null);
    }

    public static Result run(List<String> command, Duration timeout, Map<String, String> env, Path workDir) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start " + command.getFirst() + ": " + e.getMessage(), e);
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // child may already have exited
        }
        CompletableFuture<byte[]> out = drain(process.getInputStream());
        CompletableFuture<byte[]> err = drain(process.getErrorStream());
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + command.getFirst(), e);
        }
        if (!finished) {
            kill(process);
        }
        String stdout = new String(join(out), StandardCharsets.UTF_8);
        String stderr = new String(join(err), StandardCharsets.UTF_8);
        return new Result(finished ? process.exitValue() : -1, stdout, stderr, !finished);
    }

    /** Polite then forceful termination of a process and its children. */
    public static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    public static String tail(String text, int lines) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] all = text.split("\\R");
        int from = Math.max(0, all.length - lines);
        return String.join("\n", java.util.Arrays.copyOfRange(all, from, all.length));
    }

    private static CompletableFuture<byte[]> drain(InputStream in) {
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        Thread.ofVirtual().name("proc-drain").start(() -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    if (buf.size() < MAX_CAPTURE) {
                        buf.write(chunk, 0, Math.min(n, MAX_CAPTURE - buf.size()));
                    }
                }
            } catch (IOException ignored) {
                // stream closed when process is killed
            }
            f.complete(buf.toByteArray());
        });
        return f;
    }

    private static byte[] join(CompletableFuture<byte[]> f) {
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (ExecutionException | TimeoutException e) {
            return new byte[0];
        }
    }
}
