//! NeoTUN-owned boundary for an optional local DPI strategy backend.
//!
//! This module intentionally contains no packet interception implementation yet.
//! It defines the stable contract that an audited RIPDPI adapter must implement,
//! keeping upstream API changes out of the app and preserving Xray/sing-box fallback.

use std::time::Duration;

/// A destination without credentials or a share URI.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Target {
    pub host: String,
    pub port: u16,
    pub transport: Transport,
}

impl Target {
    pub fn new(host: impl Into<String>, port: u16, transport: Transport) -> Result<Self, DpiError> {
        let host = host.into().trim().to_string();
        if host.is_empty() || host.chars().any(char::is_control) {
            return Err(DpiError::InvalidTarget);
        }
        if port == 0 {
            return Err(DpiError::InvalidTarget);
        }
        Ok(Self { host, port, transport })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Transport {
    Tcp,
    Udp,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BackendState {
    NotInstalled,
    Disabled,
    UnsupportedPlatform,
    Ready,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Capabilities {
    pub tcp: bool,
    pub udp: bool,
    pub per_target_diagnostics: bool,
}

impl Capabilities {
    pub const NONE: Self = Self {
        tcp: false,
        udp: false,
        per_target_diagnostics: false,
    };

    pub fn supports(self, transport: Transport) -> bool {
        match transport {
            Transport::Tcp => self.tcp,
            Transport::Udp => self.udp,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FailureClass {
    DirectConnectionFailed,
    IpBlockSuspected,
    TlsOrSniSuspected,
    QuicOrUdpSuspected,
    UnsupportedTarget,
    PermissionDenied,
    BackendUnavailable,
    Unknown,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Diagnosis {
    pub direct_reachable: Option<bool>,
    pub failure: FailureClass,
    /// Human-readable detail must not contain UUIDs, passwords, share URIs, or keys.
    pub detail: Option<String>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Fallback {
    /// Try the existing Xray/sing-box engine for the selected profile.
    ExistingEngine,
    /// Keep the current connection untouched; the caller should report the error.
    AbortWithoutChangingConnection,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum DpiError {
    InvalidTarget,
    UnsupportedTransport,
    BackendUnavailable,
    TimedOut,
    Cancelled,
    Failed(String),
}

impl std::fmt::Display for DpiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidTarget => write!(f, "invalid DPI target"),
            Self::UnsupportedTransport => write!(f, "transport is not supported by the DPI backend"),
            Self::BackendUnavailable => write!(f, "DPI backend is unavailable"),
            Self::TimedOut => write!(f, "DPI operation timed out"),
            Self::Cancelled => write!(f, "DPI operation was cancelled"),
            Self::Failed(message) => write!(f, "DPI operation failed: {message}"),
        }
    }
}

impl std::error::Error for DpiError {}

/// The contract NeoTUN owns. An upstream adapter must not own the Android
/// VpnService/TUN lifecycle; it only exposes supported capabilities and bounded
/// diagnostics/strategy operations to the caller.
pub trait DpiBackend: Send + Sync {
    fn state(&self) -> BackendState;
    fn capabilities(&self) -> Capabilities;

    fn diagnose(
        &self,
        target: &Target,
        timeout: Duration,
    ) -> Result<Diagnosis, DpiError>;

    /// Returns Ok(true) only after the backend has verified the target using
    /// the selected strategy. Ok(false) means no usable local strategy worked.
    fn try_local_strategy(
        &self,
        target: &Target,
        timeout: Duration,
    ) -> Result<bool, DpiError>;
}

/// Decide whether NeoTUN may attempt local strategies for this destination.
pub fn local_strategy_is_applicable(
    state: BackendState,
    capabilities: Capabilities,
    target: &Target,
) -> bool {
    state == BackendState::Ready && capabilities.supports(target.transport)
}

/// The fallback is deliberately explicit: a local DPI failure must never erase
/// profiles, rewrite routing/DNS, or mark the VPN as connected.
pub fn fallback_after_local_failure() -> Fallback {
    Fallback::ExistingEngine
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn target_rejects_empty_host_and_zero_port() {
        assert_eq!(
            Target::new("  ", 443, Transport::Tcp).unwrap_err(),
            DpiError::InvalidTarget
        );
        assert_eq!(
            Target::new("example.org", 0, Transport::Tcp).unwrap_err(),
            DpiError::InvalidTarget
        );
    }

    #[test]
    fn target_rejects_control_characters() {
        assert_eq!(
            Target::new("example.org\nsecret", 443, Transport::Tcp).unwrap_err(),
            DpiError::InvalidTarget
        );
    }

    #[test]
    fn local_strategy_requires_ready_backend_and_supported_transport() {
        let target = Target::new("example.org", 443, Transport::Tcp).unwrap();
        let tcp_only = Capabilities { tcp: true, udp: false, per_target_diagnostics: true };
        assert!(local_strategy_is_applicable(BackendState::Ready, tcp_only, &target));
        assert!(!local_strategy_is_applicable(BackendState::Disabled, tcp_only, &target));

        let udp_target = Target::new("example.org", 443, Transport::Udp).unwrap();
        assert!(!local_strategy_is_applicable(BackendState::Ready, tcp_only, &udp_target));
    }

    #[test]
    fn failed_local_strategy_falls_back_to_existing_engine() {
        assert_eq!(fallback_after_local_failure(), Fallback::ExistingEngine);
    }
}
