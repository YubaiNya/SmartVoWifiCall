package runtime

import (
	"context"
	"errors"
	"strings"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/behavior"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ikev2"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/sip"
)

// Classify only results from this attempt and its verified stage. Error prose is
// never exported: DNS addresses, identities and vendor error strings stay out.
func diagnose(stage behavior.State, err error) behavior.Failure {
	f := behavior.Failure{State: behavior.Unknown, Cause: "UNCLASSIFIED_FAILURE"}
	var rejected *ikev2.AuthRejectedError
	if errors.As(err, &rejected) {
		if rejected.BackoffTrusted {
			f.Cause, f.RetryAfter, f.RetryDisabled = "EPDG_REJECTED_WITH_BACKOFF", rejected.RetryAfter, rejected.RetryDisabled
		}
		for _, n := range rejected.Notifies {
			if n == ikev2.NotifyAuthenticationFailed {
				f.State, f.Cause, f.ManualOnly = behavior.IKEAuthFailure, "AUTHENTICATION_REJECTED", true
				if rejected.Stage == "final" {
					f.Cause = "IKE_FINAL_AUTH_REJECTED"
				}
				return f
			}
		}
		return f
	}
	var reg *sip.RegistrationError
	if errors.As(err, &reg) {
		return behavior.Failure{State: behavior.IMSFailed, Cause: "SIP_REGISTRATION_REJECTED",
			ManualOnly: reg.StatusCode == 403, RetryAfter: reg.RetryAfter}
	}
	if stage == behavior.Discovery {
		return behavior.Failure{State: behavior.DNSFailure, Cause: "DNS_LOOKUP_FAILED"}
	}
	if stage == behavior.IMSRegistering {
		f.State, f.Cause = behavior.IMSFailed, "SIP_REGISTRATION_FAILED"
	}
	for current := err; current != nil; current = errors.Unwrap(current) {
		switch current.Error() {
		case "IKE_EAP_FAILURE", "IKE_AUTH_RESPONDER_PROOF_MISSING", "IKE_AUTH_RESPONDER_PROOF_INVALID", "EAP_AKA_SERVER_MAC_MISSING", "EAP_AKA_SERVER_MAC_MISMATCH":
			return behavior.Failure{State: behavior.IKEAuthFailure, Cause: "AUTHENTICATION_FAILED", ManualOnly: true}
		case "IKE_EXCHANGE_TIMEOUT":
			return behavior.Failure{State: behavior.IKETimeout, Cause: "IKE_RESPONSE_TIMEOUT"}
		case "IKE_COOKIE_INVALID", "IKE_COOKIE_RETRY_EXHAUSTED":
			return behavior.Failure{State: behavior.IKEChallengeFailure, Cause: "COOKIE_CHALLENGE_FAILED"}
		case "IKE_DH_HINT_INVALID", "IKE_DH_GROUP_NOT_OFFERED", "IKE_DH_RETRY_UNCHANGED", "IKE_DH_RETRY_EXHAUSTED", "IKE_SA_INIT_RETRY_EXHAUSTED":
			return behavior.Failure{State: behavior.IKEChallengeFailure, Cause: "IKE_NEGOTIATION_FAILED"}
		}
		if strings.HasPrefix(current.Error(), "SIP_AKA_AUTH_FAILED:") {
			f.State, f.Cause, f.ManualOnly = behavior.IMSFailed, "SIM_AUTHENTICATION_FAILED", true
		}
	}
	if stage == behavior.IKEConnecting && errors.Is(err, context.DeadlineExceeded) {
		f.State, f.Cause = behavior.IKETimeout, "IKE_OPERATION_DEADLINE"
	}
	return f
}
