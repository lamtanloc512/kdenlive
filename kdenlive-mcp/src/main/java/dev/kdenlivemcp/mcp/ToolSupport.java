package dev.kdenlivemcp.mcp;

import dev.kdenlivemcp.edit.Times;
import dev.kdenlivemcp.media.MediaException;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.tools.ToolMissingException;
import io.quarkiverse.mcp.server.ToolCallException;
import org.jboss.logging.Logger;

import java.io.UncheckedIOException;
import java.util.function.Supplier;

/** Shared plumbing for tool classes: argument conversion and error mapping. */
final class ToolSupport {

    private static final Logger LOG = Logger.getLogger(ToolSupport.class);

    private ToolSupport() {
    }

    /**
     * Runs tool logic; problems the model can fix become tool errors with the original message, anything
     * unexpected is logged and reported briefly.
     */
    static <T> T call(String tool, Supplier<T> body) {
        try {
            return body.get();
        } catch (EditException | MediaException | ToolMissingException e) {
            LOG.debugf("%s rejected: %s", tool, e.getMessage());
            throw new ToolCallException(e.getMessage());
        } catch (UncheckedIOException e) {
            LOG.errorf(e, "%s failed", tool);
            throw new ToolCallException("file error: " + e.getMessage());
        } catch (ToolCallException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.errorf(e, "%s failed", tool);
            throw new ToolCallException(tool + " failed: " + e + " (see ~/.kdenlive-mcp/logs/kdenlive-mcp.log)");
        }
    }

    static long frames(Fps fps, String name, double seconds) {
        return Times.frames(fps, name, seconds);
    }

    static Long framesOrNull(Fps fps, String name, Double seconds) {
        return seconds == null ? null : Times.frames(fps, name, seconds);
    }

    static long positive(Fps fps, String name, double seconds) {
        return Times.positive(fps, name, seconds);
    }
}
