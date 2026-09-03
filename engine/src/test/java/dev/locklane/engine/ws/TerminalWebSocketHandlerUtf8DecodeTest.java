package dev.locklane.engine.ws;

import dev.locklane.engine.ws.TerminalWebSocketHandler.StreamingUtf8Decoder;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A multi-byte UTF-8 character split across two PTY reads reaches the client intact
 * (#634): the trailing partial bytes of one chunk are joined onto the next instead of
 * each side decoding to U+FFFD on its own.
 */
class TerminalWebSocketHandlerUtf8DecodeTest {

    /** U+2500 BOX DRAWINGS LIGHT HORIZONTAL — three bytes: E2 94 80. */
    private static final String RULE = "─";

    @Test
    void aThreeByteCharacterSplitAfterItsFirstByteDecodesIntact() {
        assertSplitDecodesIntact("ab" + RULE + "cd", 3);
    }

    @Test
    void aThreeByteCharacterSplitAfterItsSecondByteDecodesIntact() {
        assertSplitDecodesIntact("ab" + RULE + "cd", 4);
    }

    @Test
    void aFourByteCharacterSplitAtEveryPositionDecodesIntact() {
        String emoji = "x😀y"; // U+1F600, four bytes
        for (int split = 1; split < 5; split++) {
            assertSplitDecodesIntact(emoji, split);
        }
    }

    @Test
    void aChunkEndingOnACharacterBoundaryIsDeliveredUnchangedAndImmediately() {
        StreamingUtf8Decoder decoder = new StreamingUtf8Decoder();
        String whole = "hello " + RULE + RULE + " world";

        assertThat(decoder.decode(whole.getBytes(StandardCharsets.UTF_8))).isEqualTo(whole);
    }

    @Test
    void aChunkHoldingOnlyAPartialCharacterSendsNothingUntilItCompletes() {
        StreamingUtf8Decoder decoder = new StreamingUtf8Decoder();
        byte[] bytes = RULE.getBytes(StandardCharsets.UTF_8);

        assertThat(decoder.decode(Arrays.copyOfRange(bytes, 0, 1))).isEmpty();
        assertThat(decoder.decode(Arrays.copyOfRange(bytes, 1, 2))).isEmpty();
        assertThat(decoder.decode(Arrays.copyOfRange(bytes, 2, 3))).isEqualTo(RULE);
    }

    @Test
    void aGenuinelyMalformedByteStillBecomesAReplacementCharacterAndDoesNotStall() {
        StreamingUtf8Decoder decoder = new StreamingUtf8Decoder();
        byte[] lead = RULE.getBytes(StandardCharsets.UTF_8);

        // First two bytes of a 3-byte sequence, then plain ASCII: the pending bytes can
        // never complete, so they are replaced and the ASCII is delivered at once.
        assertThat(decoder.decode(Arrays.copyOfRange(lead, 0, 2))).isEmpty();
        assertThat(decoder.decode("a".getBytes(StandardCharsets.US_ASCII))).isEqualTo("�a");
        assertThat(decoder.decode("b".getBytes(StandardCharsets.US_ASCII))).isEqualTo("b");
    }

    @Test
    void forwardJoinsSplitChunksAcrossSuccessiveCallsOnOneConnection() throws IOException {
        List<String> sent = new ArrayList<>();
        WebSocketSession wsSession = mock(WebSocketSession.class);
        when(wsSession.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            WebSocketMessage<?> message = invocation.getArgument(0);
            sent.add(((TextMessage) message).getPayload());
            return null;
        }).when(wsSession).sendMessage(any());
        StreamingUtf8Decoder decoder = new StreamingUtf8Decoder();
        String line = "ab" + RULE + RULE + "cd";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);

        TerminalWebSocketHandler.forward(wsSession, decoder, Arrays.copyOfRange(bytes, 0, 4));
        TerminalWebSocketHandler.forward(wsSession, decoder, Arrays.copyOfRange(bytes, 4, bytes.length));

        assertThat(String.join("", sent)).isEqualTo(line);
        assertThat(sent).noneMatch(s -> s.contains("�"));
        // The complete leading characters went out with the first chunk, not held back.
        assertThat(sent.get(0)).isEqualTo("ab");
    }

    private static void assertSplitDecodesIntact(String original, int splitAt) {
        byte[] bytes = original.getBytes(StandardCharsets.UTF_8);
        StreamingUtf8Decoder decoder = new StreamingUtf8Decoder();

        String joined = decoder.decode(Arrays.copyOfRange(bytes, 0, splitAt))
                + decoder.decode(Arrays.copyOfRange(bytes, splitAt, bytes.length));

        assertThat(joined).doesNotContain("�").isEqualTo(original);
    }
}
