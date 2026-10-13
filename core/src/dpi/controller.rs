//! Runtime settings for the DPI strategy applied to generated sing-box configs.

use super::shaper::DpiConfig;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    Idle,
    DpiBypassing,
    FallbackTunnelActive,
}

pub struct ConnectionController {
    config: DpiConfig,
}

impl ConnectionController {
    pub fn new(config: DpiConfig) -> Self {
        Self { config }
    }

    pub fn set_enabled(&mut self, enabled: bool, threshold: usize) {
        self.config.enabled = enabled;
        if threshold > 0 {
            self.config.fragmentation_threshold = threshold;
        }
    }

    pub fn is_enabled(&self) -> bool {
        self.config.enabled
    }

    pub fn get_state(&self) -> ConnectionState {
        // Configuring a strategy is not proof that a live connection has applied it.
        let _ = self.config.out_of_order;
        ConnectionState::Idle
    }
}
