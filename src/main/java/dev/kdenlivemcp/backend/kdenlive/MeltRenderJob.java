package dev.kdenlivemcp.backend.kdenlive;

import dev.kdenlivemcp.backend.RenderJob;
import dev.kdenlivemcp.backend.RenderStatus;
import dev.kdenlivemcp.tools.ProcessRunner;
import org.jboss.logging.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One melt render running in the background. Output goes to a temporary file that is moved into place only
 * when melt succeeds; cancel/timeout kill the process tree and delete the partial file.
 */
final class MeltRenderJob implements RenderJob {

    private static final Logger LOG = Logger.getLogger(MeltRenderJob.class);
    private static final Pattern PROGRESS = Pattern.compile("Current Frame:\\s*(\\d+),\\s*percentage:\\s*(\\d+)");

    private final String id;
    private final List<String> command;
    private final Map<String, String> env;
    private final Path tempOutput;
    private final Path output;
    private final Path logFile;
    private final long totalFrames;
    private final Duration timeout;
    private final Function<Path, String> verify;
    private final Deque<String> tail = new ArrayDeque<>();
    private final long startedAt = System.nanoTime();

    private volatile RenderStatus.State state = RenderStatus.State.QUEUED;
    private volatile int percent;
    private volatile String message = "queued";
    private volatile Process process;

    /**
     * @param verify checks the finished file and returns a one-line description (throws if it is unusable)
     */
    MeltRenderJob(String id, List<String> command, Map<String, String> env, Path tempOutput, Path output, Path logFile,
                  long totalFrames, Duration timeout, Function<Path, String> verify) {
        this.id = id;
        this.command = List.copyOf(command);
        this.env = Map.copyOf(env);
        this.tempOutput = tempOutput;
        this.output = output;
        this.logFile = logFile;
        this.totalFrames = Math.max(1, totalFrames);
        this.timeout = timeout;
        this.verify = verify;
    }

    void start() {
        Thread.ofVirtual().name("render-" + id).start(this::run);
    }

    private void run() {
        try (BufferedWriter log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
            log.write("$ " + String.join(" ", command) + "\n");
            log.write("env " + env + "\n");
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            pb.environment().putAll(env);
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            synchronized (this) {
                if (state == RenderStatus.State.CANCELLED) {
                    return;
                }
                process = pb.start();
                state = RenderStatus.State.RUNNING;
                message = "rendering";
            }
            process.getOutputStream().close();
            Thread reader = Thread.ofVirtual().start(() -> pump(process.getInputStream(), log));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                ProcessRunner.kill(process);
                reader.join(5000);
                fail("timed out after " + timeout.toSeconds() + "s");
                return;
            }
            reader.join(5000);
            if (state == RenderStatus.State.CANCELLED) {
                return;
            }
            int exit = process.exitValue();
            if (exit != 0 || !Files.isRegularFile(tempOutput) || Files.size(tempOutput) == 0) {
                fail("melt exited with code " + exit);
                return;
            }
            Files.move(tempOutput, output, StandardCopyOption.REPLACE_EXISTING);
            String desc = verify.apply(output);
            percent = 100;
            message = "done -> " + output + " (" + desc + ") in " + elapsed();
            state = RenderStatus.State.DONE;
            LOG.infof("render %s %s", id, message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    private void pump(InputStream in, BufferedWriter log) {
        StringBuilder line = new StringBuilder();
        try (in) {
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\r' || c == '\n') {
                    if (!line.isEmpty()) {
                        handleLine(line.toString(), log);
                        line.setLength(0);
                    }
                } else {
                    line.append((char) c);
                }
            }
            if (!line.isEmpty()) {
                handleLine(line.toString(), log);
            }
        } catch (IOException ignored) {
            // process killed
        }
    }

    private void handleLine(String line, BufferedWriter log) throws IOException {
        Matcher m = PROGRESS.matcher(line);
        if (m.find()) {
            long frame = Long.parseLong(m.group(1));
            percent = (int) Math.min(99, frame * 100 / totalFrames);
            return; // progress lines are not logged (thousands of them)
        }
        log.write(line);
        log.write('\n');
        log.flush();
        synchronized (tail) {
            tail.addLast(line);
            while (tail.size() > 20) {
                tail.removeFirst();
            }
        }
    }

    private void fail(String why) {
        if (state == RenderStatus.State.CANCELLED) {
            return;
        }
        state = RenderStatus.State.FAILED;
        message = "failed: " + why + " (log: " + logFile + ")";
        deleteQuietly(tempOutput);
        LOG.warnf("render %s %s", id, message);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public RenderStatus status() {
        String eta = "";
        if (state == RenderStatus.State.RUNNING && percent > 2) {
            double secs = (System.nanoTime() - startedAt) / 1e9;
            eta = String.format(java.util.Locale.ROOT, ", eta %.0fs", secs * (100 - percent) / percent);
        }
        String m = state == RenderStatus.State.RUNNING ? "rendering " + percent + "% (" + elapsed() + " elapsed" + eta + ")"
                : message;
        String logTail;
        synchronized (tail) {
            logTail = String.join("\n", tail);
        }
        return new RenderStatus(state, percent, m, logTail);
    }

    @Override
    public synchronized void cancel() {
        if (state.ordinal() >= RenderStatus.State.DONE.ordinal()) {
            return;
        }
        state = RenderStatus.State.CANCELLED;
        message = "cancelled after " + elapsed();
        if (process != null) {
            ProcessRunner.kill(process);
        }
        deleteQuietly(tempOutput);
    }

    Path output() {
        return output;
    }

    private String elapsed() {
        return String.format(java.util.Locale.ROOT, "%.1fs", (System.nanoTime() - startedAt) / 1e9);
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
