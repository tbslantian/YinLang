package app.soundshelf;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

public class HttpCancellationTest {
    @Test public void cancelledRuntimeCannotStartAnotherRequest() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var cancellation = new Http.Cancellation();
            cancellation.cancel();
            assertThrows(java.io.InterruptedIOException.class, () -> fetch(server.url("/").toString(), cancellation));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void cancellingActiveRequestReleasesItAndDoesNotAffectNewSource() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var release = new CountDownLatch(1);
        try (var server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    if ("/old".equals(request.getPath())) release.await(5, TimeUnit.SECONDS);
                    return new MockResponse().setBody("new source");
                }
            });
            server.start();
            var cancellation = new Http.Cancellation();
            var old = executor.submit(() -> {
                try { fetch(server.url("/old").toString(), cancellation); return false; }
                catch (IOException cancelled) { return true; }
            });
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            try {
                long started = System.nanoTime();
                cancellation.cancel();
                assertTrue("cancellation must not block the UI", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500);
                assertTrue(old.get(2, TimeUnit.SECONDS));
                assertEquals("new source", fetch(server.url("/new").toString(), new Http.Cancellation()).body());
                assertThrows(java.io.InterruptedIOException.class, () -> fetch(server.url("/retry").toString(), cancellation));
                assertEquals(2, server.getRequestCount());
            } finally { release.countDown(); }
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void tokenRemainsCancelledAfterCompletedRedirects() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/next"));
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();
            var cancellation = new Http.Cancellation();
            assertEquals("ok", fetch(server.url("/first").toString(), cancellation).body());
            cancellation.cancel();
            assertThrows(java.io.InterruptedIOException.class, () -> fetch(server.url("/again").toString(), cancellation));
            assertEquals(2, server.getRequestCount());
        }
    }

    private static Http.Response fetch(String url, Http.Cancellation cancellation) throws IOException {
        return Http.request(url, "GET", Map.of(), null, true, false, "", false, 10_000, cancellation);
    }
}
