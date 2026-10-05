package com.payflow.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class RegisterRequest {

    /**
     * The user's payment handle, for example "sai123". It replaces the phone
     * number as the thing another person types to send this user money, so the
     * format is constrained here as well as being canonicalised in
     * HandleService: validation gives the caller a clear error, normalisation
     * is what actually makes two spellings the same identity.
     */
    @NotBlank(message = "Handle is required")
    @Size(min = 3, max = 30, message = "Handle must be between 3 and 30 characters")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._]{2,29}$",
            message = "Handle must start with a letter or digit and contain only letters, digits, dot or underscore")
    private String username;

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 6, max = 100, message = "Password must be at least 6 characters")
    private String password;
}
