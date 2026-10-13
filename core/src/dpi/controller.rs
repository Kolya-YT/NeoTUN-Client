//! State holder for the experimental DPI settings API.
//! This controller deliberately does not claim to shape traffic until a real
//! packet-processing implementation is integrated.

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

    pub fn get_state(&self) -> ConnectionState {
        // No packet shaper is wired into the data path yet. Returning Idle avoids
        // falsely reporting DPI bypass or a fallback tunnel as active.
        let _ = self.config.out_of_order;
        ConnectionState::Idle
    }
}
