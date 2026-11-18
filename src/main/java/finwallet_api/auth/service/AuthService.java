package finwallet_api.auth.service;

import finwallet_api.auth.dto.LoginRequest;
import finwallet_api.auth.dto.LoginResponse;
import finwallet_api.common.exception.AccountClosedException;
import finwallet_api.common.exception.AccountSuspendedException;
import finwallet_api.common.exception.InvalidCredentialsException;
import finwallet_api.common.security.JwtService;
import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String GENERIC_INVALID_CREDENTIALS = "Invalid email or password";
    private static final String SUSPENDED_STATUS = "SUSPENDED";
    private static final String CLOSED_STATUS = "CLOSED";
    private static final String ACTIVE_STATUS = "ACTIVE";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String email = request.email() != null ? request.email().trim().toLowerCase(Locale.ROOT) : "";
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException(GENERIC_INVALID_CREDENTIALS);
        }

        if (SUSPENDED_STATUS.equalsIgnoreCase(user.getStatus())) {
            throw new AccountSuspendedException("Account is suspended");
        }

        if (CLOSED_STATUS.equalsIgnoreCase(user.getStatus())) {
            throw new AccountClosedException("Account is closed");
        }

        if (!ACTIVE_STATUS.equalsIgnoreCase(user.getStatus())) {
            throw new InvalidCredentialsException(GENERIC_INVALID_CREDENTIALS);
        }

        String token = jwtService.generateToken(user.getEmail(), user.getId());
        return new LoginResponse(token, jwtService.getExpirationSeconds());
    }
}
