package com.payflow.payment.controller;

import com.payflow.payment.dto.GatewayUserContext;
import com.payflow.payment.dto.TopUpRequest;
import com.payflow.payment.dto.WalletResponse;
import com.payflow.payment.security.AuthenticatedUserProvider;
import com.payflow.payment.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own wallet. There is no endpoint for reading somebody else's
 * wallet and there is no endpoint for changing a balance other than topping up
 * your own: a balance is not something a client can set.
 */
@RestController
@RequestMapping("/wallets")
public class WalletController {

    private static final String DEFAULT_CURRENCY = "USD";

    private final WalletService walletService;
    private final AuthenticatedUserProvider authenticatedUser;

    public WalletController(WalletService walletService, AuthenticatedUserProvider authenticatedUser) {
        this.walletService = walletService;
        this.authenticatedUser = authenticatedUser;
    }

    @GetMapping("/me")
    public WalletResponse myWallet() {
        GatewayUserContext principal = authenticatedUser.currentUser();
        return walletService.view(principal.ownerId());
    }

    @PostMapping("/top-up")
    public WalletResponse topUp(@Valid @RequestBody TopUpRequest request) {
        GatewayUserContext principal = authenticatedUser.currentUser();
        return walletService.topUp(principal.ownerId(), principal.handle(), request.amount(),
                request.currency() == null ? DEFAULT_CURRENCY : request.currency());
    }
}
