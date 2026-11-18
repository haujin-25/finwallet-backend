package finwallet_api.user.dto;

public record UserResponse(
    Long id,
    String email,
    String fullName,
    String status
) {}