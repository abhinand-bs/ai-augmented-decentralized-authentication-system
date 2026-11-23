package com.blockauth.alert;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.blockauth.admin.AdminController.ProofBody;
import com.blockauth.auth.StepUpProof;
import com.blockauth.security.BlockAuthPrincipal;
import com.blockauth.security.ClientInfoResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** The caller's own alert e-mail address. */
@RestController
@RequestMapping("/api/users/alert-email")
public class AlertEmailController {

	public record SetRequest(String email, @NotNull(message = "is required") @Valid StepUpProof proof) {
	}

	public record VerifyRequest(String code) {
	}

	private final AlertEmailService service;

	public AlertEmailController(AlertEmailService service) {
		this.service = service;
	}

	@GetMapping
	AlertEmailService.Status status(@AuthenticationPrincipal BlockAuthPrincipal principal) {
		return service.status(principal);
	}

	@PostMapping
	AlertEmailService.Status set(@Valid @RequestBody SetRequest body, @AuthenticationPrincipal BlockAuthPrincipal principal,
			HttpServletRequest request) {
		return service.set(principal, body.email(), body.proof(), ClientInfoResolver.resolve(request));
	}

	@PostMapping("/verify")
	AlertEmailService.Status verify(@RequestBody VerifyRequest body, @AuthenticationPrincipal BlockAuthPrincipal principal,
			HttpServletRequest request) {
		return service.verify(principal, body.code(), ClientInfoResolver.resolve(request));
	}

	@PostMapping("/remove")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void remove(@Valid @RequestBody ProofBody body, @AuthenticationPrincipal BlockAuthPrincipal principal,
			HttpServletRequest request) {
		service.remove(principal, body.proof(), ClientInfoResolver.resolve(request));
	}

}
