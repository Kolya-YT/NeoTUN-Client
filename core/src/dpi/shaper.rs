//! DPI shaping configuration.
//! Packet fragmentation is not implemented here; keep the feature inert rather than
//! reporting successful shaping when no traffic transformation is performed.

#[derive(Debug, Clone, Copy)]
pub struct DpiConfig {
    pub enabled: bool,
    pub fragmentation_threshold: usize,
    pub out_of_order: bool,
}
