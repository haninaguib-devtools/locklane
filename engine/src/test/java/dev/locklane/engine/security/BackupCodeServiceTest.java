package dev.locklane.engine.security;

import dev.locklane.engine.persistence.BackupCodeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BackupCodeService} against a mocked {@link BackupCodeRepository}: the shape of a
 * freshly generated set, and the match-then-consume logic {@link AuthController} relies on
 * at login (#93). A real {@link BCryptPasswordEncoder} is used throughout rather than a
 * stub, since the hashing itself is exactly what {@link #consume} has to see through.
 */
class BackupCodeServiceTest {

    private final BackupCodeRepository repository = mock(BackupCodeRepository.class);
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final BackupCodeService service = new BackupCodeService(repository, passwordEncoder);

    @Test
    @SuppressWarnings("unchecked")
    void regenerateProducesTenDistinctFormattedCodesAndStoresTheirHashes() {
        List<String> codes = service.regenerate(1L, Instant.now());

        assertThat(codes).hasSize(10);
        codes.forEach(code -> assertThat(code).matches("[0-9A-F]{5}-[0-9A-F]{5}"));
        assertThat(codes).doesNotHaveDuplicates();

        ArgumentCaptor<List<String>> hashesCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).replace(eq(1L), hashesCaptor.capture(), any());
        List<String> hashes = hashesCaptor.getValue();
        assertThat(hashes).hasSize(10);
        for (int i = 0; i < codes.size(); i++) {
            assertThat(passwordEncoder.matches(codes.get(i), hashes.get(i))).isTrue();
        }
    }

    @Test
    void consumeMatchesAgainstAStoredHashCaseInsensitivelyAndMarksTheRowUsed() {
        String hash = passwordEncoder.encode("ABCDE-12345");
        when(repository.findUnused(1L)).thenReturn(List.of(new BackupCodeRepository.BackupCodeRow(7L, hash)));
        when(repository.markUsed(eq(7L), any())).thenReturn(true);

        assertThat(service.consume(1L, "abcde-12345", Instant.now())).isTrue();
        verify(repository).markUsed(eq(7L), any());
    }

    @Test
    void consumeRejectsACodeThatMatchesNoStoredHash() {
        when(repository.findUnused(1L)).thenReturn(List.of(
                new BackupCodeRepository.BackupCodeRow(7L, passwordEncoder.encode("ABCDE-12345"))));

        assertThat(service.consume(1L, "wrong-code", Instant.now())).isFalse();
        verify(repository, never()).markUsed(anyLong(), any());
    }

    @Test
    void consumeReportsFalseWhenAnotherRequestConsumedTheSameCodeFirst() {
        String hash = passwordEncoder.encode("ABCDE-12345");
        when(repository.findUnused(1L)).thenReturn(List.of(new BackupCodeRepository.BackupCodeRow(7L, hash)));
        when(repository.markUsed(eq(7L), any())).thenReturn(false);

        assertThat(service.consume(1L, "ABCDE-12345", Instant.now())).isFalse();
    }

    @Test
    void consumeRejectsNullOrBlankInputWithoutTouchingTheRepository() {
        assertThat(service.consume(1L, null, Instant.now())).isFalse();
        assertThat(service.consume(1L, "   ", Instant.now())).isFalse();
        verify(repository, never()).findUnused(anyLong());
    }
}
