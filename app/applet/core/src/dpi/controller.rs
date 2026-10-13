use crate::dpi::shaper::{DpiConfig, DpiShaper};
use std::time::{Instant, Duration};

pub enum ConnectionState {
    DpiBypassing,
    FallbackTunnelActive,
}

pub struct ConnectionController {
    shaper: DpiShaper,
    state: ConnectionState,
    last_probe: Instant,
    probe_interval: Duration,
}

impl ConnectionController {
    pub fn new(config: DpiConfig) -> Self {
        Self {
            shaper: DpiShaper::new(config),
            state: ConnectionState::DpiBypassing,
            last_probe: Instant::now(),
            probe_interval: Duration::from_secs(600), // 10 minutes
        }
    }

    pub fn set_enabled(&mut self, enabled: bool, threshold: usize) {
        self.shaper = DpiShaper::new(DpiConfig { enabled, fragmentation_threshold: threshold, out_of_order: false });
    }

    pub fn get_state(&self) -> &ConnectionState {
        &self.state
    }

    pub fn handle_failure(&mut self) {
        self.state = ConnectionState::FallbackTunnelActive;
    }

    pub fn update(&mut self) {
        if let ConnectionState::FallbackTunnelActive = self.state {
            if self.last_probe.elapsed() >= self.probe_interval {
                // Time to re-probe
                self.state = ConnectionState::DpiBypassing;
                self.last_probe = Instant::now();
            }
        }
    }
}
