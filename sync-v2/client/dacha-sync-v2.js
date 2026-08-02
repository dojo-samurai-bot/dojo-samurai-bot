(function () {
  'use strict';

  const DB = window.DachaDB;
  const PROTOCOL_VERSION = 2;
  const ACTIVE_STATUSES = new Set(['pending', 'sending', 'failed', 'conflict']);
  let config = null;
  let syncPromise = null;
  let pollTimer = null;
  let deviceId = null;

  function clone(value) {
    return value == null ? value : JSON.parse(JSON.stringify(value));
  }

  function uuid() {
    if (window.crypto?.randomUUID) return window.crypto.randomUUID();
    const bytes = new Uint8Array(16);
    window.crypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
      .replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, '$1-$2-$3-$4-$5');
  }

  function nowIso() { return new Date().toISOString(); }
  function entityKey(store, id) { return `${store}:${id}`; }
  function versionKey(store, id) { return `entityVersion:${store}:${id}`; }

  function retryDelayMs(retryCount) {
    const base = Math.min(5 * 60 * 1000, 2000 * Math.pow(2, Math.max(0, retryCount - 1)));
    return base + Math.floor(Math.random() * 1000);
  }

  function changedFields(previous, next) {
    const out = {};
    const keys = new Set([...Object.keys(previous || {}), ...Object.keys(next || {})]);
    for (const key of keys) {
      if (JSON.stringify(previous?.[key]) !== JSON.stringify(next?.[key])) out[key] = clone(next?.[key]);
    }
    return out;
  }

  async function ensureDeviceId() {
    if (deviceId) return deviceId;
    deviceId = await DB.getMeta('syncDeviceId', '');
    if (!deviceId) {
      deviceId = uuid();
      await DB.setMeta('syncDeviceId', deviceId);
    }
    if ((await DB.getMeta('lastServerSequence', null)) == null) await DB.setMeta('lastServerSequence', 0);
    return deviceId;
  }

  function enabled() {
    return Boolean(config?.endpoint);
  }

  function configure(options = {}) {
    config = {
      endpoint: String(options.endpoint || '').trim(),
      apiKey: String(options.apiKey || ''),
      deviceName: String(options.deviceName || 'Устройство'),
      appVersion: String(options.appVersion || ''),
      pollMs: Math.max(30000, Number(options.pollMs || 120000)),
      batchSize: Math.max(1, Math.min(200, Number(options.batchSize || 50))),
      requestTimeoutMs: Math.max(5000, Number(options.requestTimeoutMs || 30000)),
      onStatus: typeof options.onStatus === 'function' ? options.onStatus : () => {},
      onRemoteChange: typeof options.onRemoteChange === 'function' ? options.onRemoteChange : () => {},
      onConflict: typeof options.onConflict === 'function' ? options.onConflict : () => {},
    };
    restartPolling();
    return enabled();
  }

  async function createOperation(store, id, action, payload = {}) {
    const currentDeviceId = await ensureDeviceId();
    return {
      operationId: uuid(),
      protocolVersion: PROTOCOL_VERSION,
      schemaVersion: 2,
      deviceId: currentDeviceId,
      entityKey: entityKey(store, id),
      entityType: store,
      entityId: id,
      action,
      changes: clone(payload.changes || {}),
      appendField: payload.appendField || null,
      appendValue: clone(payload.appendValue),
      criticalFields: [...new Set(payload.criticalFields || [])],
      baseVersion: Number(await DB.getMeta(versionKey(store, id), 0)),
      clientCreatedAt: nowIso(),
      status: 'pending',
      retryCount: 0,
      nextAttemptAt: 0,
      lastError: null,
    };
  }

  async function put(store, nextRecord, criticalFields = []) {
    if (!enabled()) return DB.put(store, nextRecord);
    const previous = await DB.get(store, nextRecord.id);
    const changes = changedFields(previous || {}, nextRecord);
    if (!Object.keys(changes).length) return nextRecord;
    const operation = await createOperation(store, nextRecord.id, 'patch', { changes, criticalFields });
    await DB.putWithOperation(store, nextRecord, operation);
    emitStatus('local-change');
    scheduleSync(2500);
    return nextRecord;
  }

  async function remove(store, id) {
    if (!enabled()) return DB.remove(store, id);
    const operation = await createOperation(store, id, 'delete', { criticalFields: ['__deleted__'] });
    await DB.removeWithOperation(store, id, operation);
    emitStatus('local-change');
    scheduleSync(2500);
  }

  async function syncNow(reason = 'manual') {
    if (!enabled()) return { ok: false, disabled: true };
    if (syncPromise) return syncPromise;
    syncPromise = performSync(reason).finally(() => { syncPromise = null; });
    return syncPromise;
  }

  async function performSync(reason) {
    await ensureDeviceId();
    if (navigator.onLine === false) {
      emitStatus('offline', { reason });
      return { ok: false, offline: true };
    }

    emitStatus('syncing', { reason });
    let sent = 0;
    let received = 0;
    let pages = 0;

    try {
      do {
        const operations = pages === 0 ? await dueOperations(config.batchSize) : [];
        await markSending(operations);
        sent += operations.length;

        const afterSequence = Number(await DB.getMeta('lastServerSequence', 0));
        const response = await postJson({
          action: 'sync',
          protocolVersion: PROTOCOL_VERSION,
          apiKey: config.apiKey,
          deviceId,
          deviceName: config.deviceName,
          appVersion: config.appVersion,
          afterSequence,
          maxEvents: 250,
          diagnostics: await diagnostics(),
          operations: operations.map(wireOperation),
        });

        if (!response?.ok) throw new Error(response?.error || 'Сервер синхронизации вернул ошибку');
        await applyAcknowledgements(response.acknowledgements || []);
        await applyConflicts(response.conflicts || []);
        await applyEvents(response.events || []);
        received += (response.events || []).length;
        pages += 1;
        if (!response.hasMore || pages >= 20) break;
      } while (true);

      await cleanupAcknowledged();
      await DB.setMeta('lastSyncAt', nowIso());
      await DB.setMeta('lastSyncError', '');
      const info = await diagnostics();
      emitStatus(info.conflictCount ? 'conflict' : 'synced', { reason, sent, received, diagnostics: info });
      return { ok: true, sent, received, diagnostics: info };
    } catch (error) {
      await failSending(error);
      await DB.setMeta('lastSyncError', String(error?.message || error));
      emitStatus('error', { reason, error: String(error?.message || error) });
      throw error;
    }
  }

  async function dueOperations(limit) {
    const all = await DB.getAll('syncOutbox');
    const now = Date.now();
    return all
      .filter(op => ['pending', 'failed'].includes(op.status) && Number(op.nextAttemptAt || 0) <= now)
      .sort((a, b) => String(a.clientCreatedAt).localeCompare(String(b.clientCreatedAt)))
      .slice(0, limit);
  }

  async function markSending(operations) {
    for (const operation of operations) {
      await DB.updateOutbox(operation.operationId, current => ({ ...current, status: 'sending', sendingAt: nowIso() }));
    }
  }

  async function failSending(error) {
    const all = await DB.getAll('syncOutbox');
    for (const operation of all.filter(op => op.status === 'sending')) {
      await DB.updateOutbox(operation.operationId, current => {
        const retryCount = Number(current.retryCount || 0) + 1;
        return {
          ...current,
          status: 'failed',
          retryCount,
          nextAttemptAt: Date.now() + retryDelayMs(retryCount),
          lastError: String(error?.message || error),
        };
      });
    }
  }

  async function applyAcknowledgements(acks) {
    for (const ack of acks) {
      await DB.updateOutbox(ack.operationId, current => ({
        ...current,
        status: ack.status === 'conflict' ? 'conflict' : 'acked',
        serverSequence: ack.serverSequence == null ? null : Number(ack.serverSequence),
        serverVersion: ack.entityVersion == null ? null : Number(ack.entityVersion),
        acknowledgedAt: nowIso(),
        lastError: ack.error || null,
      }));
      const operation = await DB.get('syncOutbox', ack.operationId);
      if (operation && ack.entityVersion != null && ack.status !== 'conflict') {
        await DB.setMeta(versionKey(operation.entityType, operation.entityId), Number(ack.entityVersion));
      }
    }
  }

  async function applyConflicts(conflicts) {
    for (const conflict of conflicts) {
      await DB.storeConflict(conflict);
      config.onConflict(clone(conflict));
    }
  }

  function applyOperationToRecord(record, operation) {
    let next = clone(record || {});
    if (operation.action === 'patch') next = { ...next, ...clone(operation.changes || {}) };
    if (operation.action === 'replace') next = clone(operation.changes || {});
    if (operation.action === 'append' && operation.appendField) {
      const list = Array.isArray(next[operation.appendField]) ? next[operation.appendField].slice() : [];
      list.push(clone(operation.appendValue));
      next[operation.appendField] = list;
    }
    if (operation.action === 'delete') return null;
    if (operation.action === 'restore') return next;
    return next;
  }

  async function applyEvents(events) {
    for (const event of events) {
      if (!event?.operationId || !event?.entity) continue;
      const alreadyApplied = await DB.get('syncAppliedOps', event.operationId);
      if (alreadyApplied) {
        const current = Number(await DB.getMeta('lastServerSequence', 0));
        if (Number(event.serverSequence || 0) > current) await DB.setMeta('lastServerSequence', Number(event.serverSequence));
        continue;
      }

      const store = event.entity.entityType;
      const id = event.entity.entityId;
      let rebased = event.entity.deleted ? null : clone(event.entity.data || {});
      const operations = (await DB.getOperationsForEntity(entityKey(store, id)))
        .filter(op => ACTIVE_STATUSES.has(op.status))
        .sort((a, b) => String(a.clientCreatedAt).localeCompare(String(b.clientCreatedAt)));
      for (const operation of operations) rebased = applyOperationToRecord(rebased, operation);
      if (rebased && !rebased.id) rebased.id = id;

      await DB.applyRemoteEntity(event, rebased, operations.length);
      config.onRemoteChange({
        store,
        id,
        deleted: event.entity.deleted || rebased == null,
        record: clone(rebased),
        event: clone(event),
        pendingCount: operations.length,
      });
    }
  }

  async function cleanupAcknowledged() {
    const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
    const all = await DB.getAll('syncOutbox');
    for (const operation of all) {
      if (operation.status === 'acked' && Date.parse(operation.acknowledgedAt || 0) < cutoff) {
        await DB.remove('syncOutbox', operation.operationId);
      }
    }
  }

  function wireOperation(operation) {
    return {
      operationId: operation.operationId,
      protocolVersion: operation.protocolVersion,
      schemaVersion: operation.schemaVersion,
      deviceId: operation.deviceId,
      entityType: operation.entityType,
      entityId: operation.entityId,
      action: operation.action,
      changes: clone(operation.changes || {}),
      appendField: operation.appendField,
      appendValue: clone(operation.appendValue),
      criticalFields: clone(operation.criticalFields || []),
      baseVersion: Number(operation.baseVersion || 0),
      clientCreatedAt: operation.clientCreatedAt,
    };
  }

  async function postJson(payload) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), config.requestTimeoutMs);
    try {
      const response = await fetch(config.endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain;charset=utf-8' },
        body: JSON.stringify(payload),
        cache: 'no-store',
        signal: controller.signal,
      });
      if (!response.ok) throw new Error(`Ошибка сервера: HTTP ${response.status}`);
      return response.json();
    } finally {
      clearTimeout(timer);
    }
  }

  async function diagnostics() {
    await ensureDeviceId();
    const outbox = await DB.getAll('syncOutbox');
    const conflicts = await DB.getAll('syncConflicts');
    return {
      deviceId,
      lastServerSequence: Number(await DB.getMeta('lastServerSequence', 0)),
      pendingCount: outbox.filter(op => ACTIVE_STATUSES.has(op.status)).length,
      failedCount: outbox.filter(op => op.status === 'failed').length,
      conflictCount: conflicts.filter(item => !String(item.status || '').startsWith('resolved')).length,
      lastSyncAt: await DB.getMeta('lastSyncAt', ''),
      lastSyncError: await DB.getMeta('lastSyncError', ''),
    };
  }

  async function emitStatus(state, details = {}) {
    if (!config) return;
    try {
      config.onStatus({ state, at: nowIso(), ...details, diagnostics: details.diagnostics || await diagnostics() });
    } catch (error) {
      console.warn('Sync v2 status callback failed', error);
    }
  }

  let scheduledTimer = null;
  function scheduleSync(delay = 900) {
    if (!enabled()) return;
    clearTimeout(scheduledTimer);
    scheduledTimer = setTimeout(() => syncNow('scheduled').catch(() => {}), delay);
  }

  function restartPolling() {
    clearInterval(pollTimer);
    pollTimer = null;
    if (!enabled()) return;
    pollTimer = setInterval(() => {
      if (document.visibilityState === 'visible') syncNow('interval').catch(() => {});
    }, config.pollMs);
  }

  window.addEventListener('online', () => scheduleSync(250));
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') scheduleSync(250);
  });

  window.DachaSyncV2 = {
    PROTOCOL_VERSION,
    configure,
    enabled,
    put,
    remove,
    syncNow,
    scheduleSync,
    diagnostics,
    restartPolling,
  };
})();
