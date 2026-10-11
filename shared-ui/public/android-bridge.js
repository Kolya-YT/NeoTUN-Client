/* Android-only adapter shim. Loaded before the React bundle in the packaged WebView. */
(() => {
  if (!window.NeoTunAndroid || window.neoTunNativeBridge) return;
  let sequence = 0;
  let previous = null;
  let pollHandle = null;
  const pending = new Map();
  const listeners = new Set();

  window.__neoTunResolve = (requestId, serializedResponse) => {
    const resolve = pending.get(requestId);
    if (!resolve) return;
    pending.delete(requestId);
    try { resolve(JSON.parse(serializedResponse)); }
    catch { resolve({ bridgeVersion: 1, requestId, ok: false, error: { code: 'INVALID_RESPONSE', message: 'Некорректный ответ Android', retryable: false } }); }
  };

  const request = (command) => new Promise((resolve) => {
    const requestId = 'android-' + (++sequence);
    pending.set(requestId, resolve);
    try {
      window.NeoTunAndroid.request(JSON.stringify({ bridgeVersion: 1, requestId, command }));
    } catch {
      pending.delete(requestId);
      resolve({ bridgeVersion: 1, requestId, ok: false, error: { code: 'BRIDGE_UNAVAILABLE', message: 'Связь с Android недоступна', retryable: true } });
    }
  });

  const publish = async () => {
    if (!listeners.size) return;
    const response = await request({ type: 'app.getState' });
    if (!response || !response.ok) return;
    const next = response.result;
    if (previous) {
      if (JSON.stringify(previous.connection) !== JSON.stringify(next.connection)) {
        listeners.forEach(listener => listener({ bridgeVersion: 1, type: 'connection.changed', state: next.connection }));
      }
      if (JSON.stringify(previous.traffic) !== JSON.stringify(next.traffic)) {
        listeners.forEach(listener => listener({ bridgeVersion: 1, type: 'traffic.updated', stats: next.traffic }));
      }
    }
    if (!previous || JSON.stringify(previous) !== JSON.stringify(next)) {
      listeners.forEach(listener => listener({ bridgeVersion: 1, type: 'app.stateChanged', snapshot: next }));
    }
    previous = next;
  };

  window.neoTunNativeBridge = {
    request,
    subscribe(listener) {
      listeners.add(listener);
      if (!pollHandle) {
        void publish();
        pollHandle = window.setInterval(() => { void publish(); }, 1200);
      }
      return () => {
        listeners.delete(listener);
        if (!listeners.size && pollHandle) {
          window.clearInterval(pollHandle);
          pollHandle = null;
        }
      };
    }
  };
})();
