package dev.locklane.engine.security;

import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads accounts from {@link UserRepository} for Spring Security's authentication.
 * Grants exactly one authority, derived from the account's {@link UserRecord.Role}
 * (#238) — {@code ROLE_ADMIN} or {@code ROLE_USER} — so later work (#239/#240) can gate
 * admin-only actions with an ordinary {@code hasRole("ADMIN")} check.
 */
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
        return new User(user.username(), user.passwordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
    }
}
