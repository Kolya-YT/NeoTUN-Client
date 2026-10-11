/**
 * Versioned contract between the shared React UI and the native host.
 *
 * This file intentionally contains types only. Platform adapters must validate
 * incoming messages at runtime and must never trust data from the WebView.
 */

export const NATIVE_BRIDGE_VERSION = 1 as const;

export type Platform = "android" | "windows";
export type ConnectionStatus = "disconnected" | "connecting" | "connected" | "error";
export type RoutingAction = "proxy" | "direct" | "block";

export interface NativeProfile {
  id: string;
  name: string;
  protocol: string;
  endpoint: string;
  selected: boolean;
  subscriptionId?: string;
}

export interface NativeConnectionState {
  status: ConnectionStatus;
  profileId: string | null;
  since: number | null;
  error: string | null;
}

export interface NativeTrafficStats {
  receivedBytes: number;
  sentBytes: number;
  downloadBytesPerSecond: number;
  uploadBytesPerSecond: number;
  sampledAt: number;
}

export interface NativeRoutingRule {
  id: string;
  action: RoutingAction;
  domains: string[];
  ips: string[];
  enabled: boolean;
}

export interface NativeClientSettings {
  dns: {
    remote: string;
    domestic: string;
    domesticDomains: string[];
  };
  mtu: number;
  ipv6: "disabled" | "enabled" | "prefer-ipv4";
  autoConnect: boolean;
  autoUpdateSubscriptions: boolean;
}

export interface NativeCapabilities {
  connectionLifecycle: boolean;
  profileManagement: boolean;
  subscriptions: boolean;
  routing: boolean;
  dns: boolean;
  mtu: boolean;
  ipv6: boolean;
  autoConnect: boolean;
  autoUpdateSubscriptions: boolean;
  diagnostics: boolean;
  appUpdates: boolean;
}

export interface NativeAppSnapshot {
  bridgeVersion: typeof NATIVE_BRIDGE_VERSION;
  platform: Platform;
  appVersion: string;
  connection: NativeConnectionState;
  traffic: NativeTrafficStats;
  profiles: NativeProfile[];
  settings: NativeClientSettings;
  routingRules: NativeRoutingRule[];
  capabilities: NativeCapabilities;
}

export type NativeCommand =
  | { type: "app.getState" }
  | { type: "profiles.import"; payload: { source: string } }
  | { type: "profiles.select"; payload: { profileId: string } }
  | { type: "profiles.rename"; payload: { profileId: string; name: string } }
  | { type: "profiles.delete"; payload: { profileId: string } }
  | { type: "connection.connect"; payload: { profileId?: string } }
  | { type: "connection.disconnect" }
  | { type: "subscriptions.refresh"; payload: { subscriptionId?: string } }
  | { type: "routing.save"; payload: { rules: NativeRoutingRule[] } }
  | { type: "settings.save"; payload: NativeClientSettings }
  | { type: "diagnostics.run" }
  | { type: "diagnostics.clear" }
  | { type: "app.checkUpdate" }
  | { type: "app.installUpdate" };

export interface NativeRequest {
  bridgeVersion: typeof NATIVE_BRIDGE_VERSION;
  requestId: string;
  command: NativeCommand;
}

export interface NativeSuccess<T = unknown> {
  bridgeVersion: typeof NATIVE_BRIDGE_VERSION;
  requestId: string;
  ok: true;
  result: T;
}

export interface NativeFailure {
  bridgeVersion: typeof NATIVE_BRIDGE_VERSION;
  requestId: string;
  ok: false;
  error: {
    code: string;
    message: string;
    retryable: boolean;
  };
}

export type NativeResponse<T = unknown> = NativeSuccess<T> | NativeFailure;

export type NativeEvent =
  | { bridgeVersion: typeof NATIVE_BRIDGE_VERSION; type: "connection.changed"; state: NativeConnectionState }
  | { bridgeVersion: typeof NATIVE_BRIDGE_VERSION; type: "traffic.updated"; stats: NativeTrafficStats }
  | { bridgeVersion: typeof NATIVE_BRIDGE_VERSION; type: "app.stateChanged"; snapshot: NativeAppSnapshot };

export interface NeoTunNativeBridge {
  request<T = unknown>(command: NativeCommand): Promise<NativeResponse<T>>;
  subscribe(listener: (event: NativeEvent) => void): () => void;
}

/**
 * Returns a bridge only when a platform host explicitly installs one.
 * There is deliberately no simulated fallback: UI must show "unavailable"
 * rather than fabricate a successful connection or network statistics.
 */
export function getNativeBridge(): NeoTunNativeBridge | null {
  if (typeof window === "undefined") return null;
  return window.neoTunNativeBridge ?? null;
}

declare global {
  interface Window {
    neoTunNativeBridge?: NeoTunNativeBridge;
  }
}
