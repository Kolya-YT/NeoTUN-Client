//! Shared DPI strategy selection for the sing-box and Xray adapters.
//!
//! This module only selects transformations that the selected engine can actually
//! perform. It does not claim to implement packet-level strategies such as ByeDPI.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DpiStrategy {
    Disabled,
    TlsRecordFragmentation,
    Unsupported,
}

#[derive(Debug, Clone, Copy)]
pub struct DpiConfig {
    pub enabled: bool,
    /// Desired upper bound for a future packet-level shaper. Current engine adapters
    /// use their own supported fragmentation controls; this value is not yet wired
    /// to a packet-level splitter.
    pub fragmentation_threshold: usize,
    pub out_of_order: bool,
}

/// Choose the safe strategy for a profile using the shared core's normalized values.
///
/// REALITY is deliberately excluded: its handshake must not be altered by this
/// generic TLS strategy. QUIC-based protocols are also excluded because they do not
/// use a TCP TLS ClientHello stream.
pub fn select_strategy(enabled: bool, protocol: &str, security: &str) -> DpiStrategy {
    if !enabled {
        return DpiStrategy::Disabled;
    }

    let is_tls_stream = security.eq_ignore_ascii_case("tls")
        && matches!(
            protocol.to_ascii_lowercase().as_str(),
            "vless" | "vmess" | "trojan"
        );

    if is_tls_stream {
        DpiStrategy::TlsRecordFragmentation
    } else {
        DpiStrategy::Unsupported
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strategy_only_enables_supported_tls_streams() {
        assert_eq!(
            select_strategy(true, "vless", "tls"),
            DpiStrategy::TlsRecordFragmentation
        );
        assert_eq!(
            select_strategy(true, "trojan", "TLS"),
            DpiStrategy::TlsRecordFragmentation
        );
        assert_eq!(
            select_strategy(true, "vless", "reality"),
            DpiStrategy::Unsupported
        );
        assert_eq!(
            select_strategy(true, "hysteria2", "tls"),
            DpiStrategy::Unsupported
        );
        assert_eq!(
            select_strategy(false, "vless", "tls"),
            DpiStrategy::Disabled
        );
    }
}
