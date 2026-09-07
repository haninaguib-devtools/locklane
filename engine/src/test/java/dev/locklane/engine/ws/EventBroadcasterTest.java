package dev.locklane.engine.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers #761's containment in the broadcast loop itself, with fake sessions: one
 * session whose write throws — the {@code IllegalStateException} Tomcat raises when two
 * threads hit one socket, before the serializing wrapper existed — must not cost the
 * sessions after it the message, and must not stay registered to fail again next time.
 */
class EventBroadcasterTest {

    private final EventBroadcaster broadcaster = new EventBroadcaster(new ObjectMapper());

    @Test
    void aSessionWhoseSendThrowsIsDroppedAndTheOthersStillReceiveTheMessage() throws Exception {
        WebSocketSession first = openSession("a");
        WebSocketSession second = openSession("b");
        WebSocketSession third = openSession("c");
        doThrow(new IllegalStateException("The remote endpoint was in state [TEXT_PARTIAL_WRITING]"))
                .when(second).sendMessage(any());
        broadcaster.register(first);
        broadcaster.register(second);
        broadcaster.register(third);

        broadcaster.broadcast("issuesChanged");

        TextMessage expected = new TextMessage("{\"type\":\"issuesChanged\"}");
        verify(first).sendMessage(expected);
        verify(third).sendMessage(expected);
        verify(second).close(any(CloseStatus.class));
        assertThat(broadcaster.registeredSessionCount()).isEqualTo(2);

        // And the dropped session is out of every later broadcast, not just this one.
        broadcaster.broadcast("issuesChanged");
        verify(second, times(1)).sendMessage(any());
        verify(first, times(2)).sendMessage(expected);
        verify(third, times(2)).sendMessage(expected);
    }

    @Test
    void aDroppedSessionWhoseCloseAlsoThrowsIsStillOutOfTheNextBroadcast() throws Exception {
        WebSocketSession broken = openSession("b");
        WebSocketSession healthy = openSession("h");
        doThrow(new IllegalStateException("send")).when(broken).sendMessage(any());
        doThrow(new IllegalStateException("close")).when(broken).close(any(CloseStatus.class));
        broadcaster.register(broken);
        broadcaster.register(healthy);

        broadcaster.broadcast("consolesChanged");
        broadcaster.broadcast("consolesChanged");

        verify(broken, times(1)).sendMessage(any());
        verify(healthy, times(2)).sendMessage(any());
        assertThat(broadcaster.registeredSessionCount()).isEqualTo(1);
    }

    private static WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
