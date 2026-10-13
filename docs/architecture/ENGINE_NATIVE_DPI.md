# Connection resilience: engine-native capabilities

Status: **DPI-shaping feature removed from the product.** This document is retained only as historical engineering guidance; it does not authorize a DPI toggle, packet shaper, extra proxy, or automatic fragmentation mode in NeoTUN.

NeoTUN uses the selected profile's Xray or sing-box engine. Existing profile parsing, routing, DNS, and tunnel lifecycle must remain independent of any DPI-specific packet manipulation.

If connection reliability work is resumed later, it must be a separately scoped change backed by a reproducible connectivity problem, supported options in the exact bundled engine version, config validation, and Android/Windows runtime tests. Do not restore the removed packet-shaping module or UI as part of routine stability work.

References for engine capabilities, not an active feature commitment:
- Xray: https://xtls.github.io/en/config/outbounds/freedom.html
- sing-box: https://sing-box.sagernet.org/configuration/shared/tls/
