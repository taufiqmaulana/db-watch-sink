package io.datasync.server;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextPathTest {

    @Test
    void normalizesTheConfiguredPath() {
        assertEquals("/", new ContextPath(Optional.empty()).path());
        assertEquals("/", new ContextPath(Optional.of("/")).path());
        assertEquals("/datasync", new ContextPath(Optional.of("datasync")).path());
        assertEquals("/datasync", new ContextPath(Optional.of(" /datasync/ ")).path());
        assertEquals("/tools/datasync", new ContextPath(Optional.of("/tools/datasync")).path());
    }

    @Test
    void refusesPathsThatAreNotPlainSegments() {
        assertThrows(IllegalStateException.class, () -> new ContextPath(Optional.of("/data sync")));
        assertThrows(IllegalStateException.class, () -> new ContextPath(Optional.of("/a;b")));
        assertThrows(IllegalStateException.class, () -> new ContextPath(Optional.of("C:/Program Files/datasync")));
    }

    @Test
    void publicPathPutsTheForwardedPrefixInFront() {
        ContextPath root = new ContextPath(Optional.empty());
        assertEquals("/", root.publicPath(null));
        assertEquals("/datasync", root.publicPath("/datasync/"));

        ContextPath app = new ContextPath(Optional.of("/datasync"));
        assertEquals("/datasync", app.publicPath(null));
        assertEquals("/tools/datasync", app.publicPath("/tools"));
        // a prefix that is not a plain path is ignored, not copied into the cookie
        assertEquals("/datasync", app.publicPath("/x;Domain=evil.example"));
    }
}
