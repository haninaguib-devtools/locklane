package dev.locklane.engine.security;

import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/** Loads accounts from {@link UserRepository} for Spring Security's authentication. */
@Service
public class EngineUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public EngineUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserRecord user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + username));
        return new User(user.username(), user.passwordHash(), List.of());
    }
}
