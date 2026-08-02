/*
 * Moy Mir / Nash Mir Sync v2 client foundation.
 * Offline-first, operation-based synchronization for Android WebView and browsers.
 */
(function (global) {
  'use strict';

  const DB_NAME = 'moy-mir-sync-v2';
  const DB_VERSION = 1;
  const PROTOCOL_VERSION = 2;
  const ACTIVE_OUTBOX_STATUSES = new Set(['pending', 'sending', 'failed', 'conflict']);

  function uuid() {
    if (global.crypto && typeof global.crypto.randomUUID === 'function') {
      return global.crypto.randomUUID();
    }
    const bytes = new Uint8Array(16);
    global.crypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    return Array.from(bytes, (b) => b.toString(16).padStart(2, '0'))
      .join('')
      .replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, '$1-$2-$3-$4-$5');
  }

  function requestPromise(request) {
    return new Promise((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error('IndexedDB request failed'));
    });
  }

  function transactionPromise(transaction) {
    return new Promise((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error || new Error('IndexedDB transaction failed'));
      transaction.onabort = () => reject(transaction.error || new Error('IndexedDB transaction aborted'));
    });
  }

  function clone(value) {
    return value == null ? value : JSON.parse(JSON.stringify(value));
  }

  function entityKey(entityType, entityId) {
    return `${entityType}:${entityId}`;
  }

  function nowIso() {
    return new Date().toISOString();
  }

  function retryDelayMs(retryCount) {
    const base = Math.min(5 * 60 * 1000, 2000 * Math.pow(2, Math.max(0, retryCount - 1)));
    return base + Math.floor(Math.random() * 1000);
  }

  class SyncV2Engine {
    constructor(options) {
      if (!options || !options.endpoint) {
        throw new Error('SyncV2Engine requires endpoint');
      }
      this.endpoint = options.endpoint;
      this.apiKey = options.apiKey || '';
      this.batchSize = Math.max(1, Math.min(200, options.batchSize || 50));
      this.pollMs = Math.max(15000, options.pollMs || 120000);
      this.requestTimeoutMs = Math.max(5000, options.requestTimeoutMs || 30000);
      this.onStatus = typeof options.onStatus === 'function' ? options.onStatus : () => {};
      this.onEntityChanged = typeof options.onEntityChanged === 'function' ? options.onEntityChanged : () => {};
      this.onConflict = typeof options.onConflict === 'function' ? options.onConflict : () => {};
      this.db = null;
      this.deviceId = null;
      this.syncPromise = null;
      this.timer = null;
      this.started = false;
      this.boundOnline = () => this.syncNow('online').catch(() => {});
      this.boundVisibility = () => {
        if (global.document && global.document.visibilityState === 'visible') {
          this.syncNow('visible').catch(() => {});
        }
      };
    }

    async init() {
      if (this.db) return this;
      this.db = await this._openDatabase();
      this.deviceId = await this._getMeta('deviceId');
      if (!this.deviceId) {
        this.deviceId = uuid();
        await this._setMeta('deviceId', this.deviceId);
      }
      if ((await this._getMeta('lastServerSequence')) == null) {
        await this._setMeta('lastServerSequence', 0);
      }
      this._emitStatus('ready');
      return this;
    }

    start() {
      if (this.started) return;
      this.started = true;
      global.addEventListener('online', this.boundOnline);
      if (global.document) global.document.addEventListener('visibilitychange', this.boundVisibility);
      this.timer = global.setInterval(() => {
        this.syncNow('interval').catch(() => {});
      }, this.pollMs);
      this.syncNow('start').catch(() => {});
    }

    stop() {
      this.started = false;
      global.removeEventListener('online', this.boundOnline);
      if (global.document) global.document.removeEventListener('visibilitychange', this.boundVisibility);
      if (this.timer) global.clearInterval(this.timer);
      this.timer = null;
    }

    async mutate(input) {
      await this.init();
      const entityType = String(input.entityType || '').trim();
      const entityId = String(input.entityId || uuid()).trim();
      const action = input.action || 'patch';
      if (!entityType) throw new Error('entityType is required');

      const operationId = uuid();
      const key = entityKey(entityType, entityId);
      const createdAt = nowIso();
      const tx = this.db.transaction(['entities', 'outbox'], 'readwrite');
      const entities = tx.objectStore('entities');
      const outbox = tx.objectStore('outbox');
      const current = (await requestPromise(entities.get(key))) || {
        key,
        entityType,
        entityId,
        data: {},
        deleted: false,
        serverVersion: 0,
        localRevision: 0
      };

      const operation = {
        operationId,
        protocolVersion: PROTOCOL_VERSION,
        schemaVersion: Number(input.schemaVersion || 2),
        deviceId: this.deviceId,
        entityKey: key,
        entityType,
        entityId,
        action,
        changes: clone(input.changes || {}),
        appendField: input.appendField || null,
        appendValue: clone(input.appendValue),
        criticalFields: Array.isArray(input.criticalFields) ? [...new Set(input.criticalFields)] : [],
        baseVersion: Number(current.serverVersion || 0),
        clientCreatedAt: createdAt,
        status: 'pending',
        retryCount: 0,
        nextAttemptAt: 0,
        lastError: null
      };

      const updated = this._applyOperation(current, operation);
      updated.localRevision = Number(current.localRevision || 0) + 1;
      updated.localUpdatedAt = createdAt;
      updated.localUpdatedByDevice = this.deviceId;
      updated.dirty = true;

      entities.put(updated);
      outbox.put(operation);
      await transactionPromise(tx);

      this.onEntityChanged(clone(updated), { source: 'local', operationId });
      this._emitStatus('local-change');
      global.setTimeout(() => this.syncNow('local-change').catch(() => {}), 2500);
      return { entityId, operationId, entity: clone(updated) };
    }

    async getEntity(entityType, entityId) {
      await this.init();
      const tx = this.db.transaction('entities', 'readonly');
      return clone(await requestPromise(tx.objectStore('entities').get(entityKey(entityType, entityId))));
    }

    async listEntities(entityType, options) {
      await this.init();
      const tx = this.db.transaction('entities', 'readonly');
      const all = await requestPromise(tx.objectStore('entities').getAll());
      const includeDeleted = Boolean(options && options.includeDeleted);
      return all
        .filter((item) => item.entityType === entityType && (includeDeleted || !item.deleted))
        .map(clone);
    }

    async syncNow(reason) {
      await this.init();
      if (this.syncPromise) return this.syncPromise;
      this.syncPromise = this._syncLoop(reason || 'manual').finally(() => {
        this.syncPromise = null;
      });
      return this.syncPromise;
    }

    async _syncLoop(reason) {
      if (global.navigator && global.navigator.onLine === false) {
        this._emitStatus('offline', { reason });
        return { ok: false, offline: true };
      }

      this._emitStatus('syncing', { reason });
      let page = 0;
      let totalEvents = 0;
      let totalSent = 0;

      try {
        do {
          const operations = page === 0 ? await this._getDueOperations(this.batchSize) : [];
          if (operations.length) {
            await this._markSending(operations.map((op) => op.operationId));
            totalSent += operations.length;
          }

          const afterSequence = Number((await this._getMeta('lastServerSequence')) || 0);
          const response = await this._post({
            action: 'sync',
            protocolVersion: PROTOCOL_VERSION,
            apiKey: this.apiKey,
            deviceId: this.deviceId,
            afterSequence,
            maxEvents: 250,
            operations: operations.map((op) => this._wireOperation(op))
          });

          if (!response || response.ok !== true) {
            throw new Error((response && response.error) || 'Invalid synchronization response');
          }

          await this._applyAcknowledgements(response.acknowledgements || []);
          await this._applyConflicts(response.conflicts || []);
          await this._applyRemoteEvents(response.events || []);
          totalEvents += (response.events || []).length;
          page += 1;

          if (!response.hasMore || page >= 20) break;
        } while (true);

        await this._cleanupAcknowledged();
        const diagnostics = await this.getDiagnostics();
        this._emitStatus(diagnostics.conflictCount ? 'conflict' : 'synced', {
          reason,
          sent: totalSent,
          received: totalEvents,
          diagnostics
        });
        return { ok: true, sent: totalSent, received: totalEvents, diagnostics };
      } catch (error) {
        await this._markSendingFailed(error);
        this._emitStatus('error', { reason, error: String(error && error.message ? error.message : error) });
        throw error;
      }
    }

    async getDiagnostics() {
      await this.init();
      const tx = this.db.transaction(['outbox', 'conflicts'], 'readonly');
      const outbox = await requestPromise(tx.objectStore('outbox').getAll());
      const conflicts = await requestPromise(tx.objectStore('conflicts').getAll());
      return {
        deviceId: this.deviceId,
        lastServerSequence: Number((await this._getMeta('lastServerSequence')) || 0),
        pendingCount: outbox.filter((op) => ACTIVE_OUTBOX_STATUSES.has(op.status)).length,
        failedCount: outbox.filter((op) => op.status === 'failed').length,
        conflictCount: conflicts.filter((item) => item.status !== 'resolved').length,
        lastSyncAt: (await this._getMeta('lastSyncAt')) || null,
        lastSyncError: (await this._getMeta('lastSyncError')) || null
      };
    }

    async resolveConflict(conflictId, resolution) {
      await this.init();
      const tx = this.db.transaction('conflicts', 'readwrite');
      const store = tx.objectStore('conflicts');
      const conflict = await requestPromise(store.get(conflictId));
      if (!conflict) throw new Error('Conflict not found');
      conflict.status = 'resolved-locally';
      conflict.resolution = clone(resolution);
      conflict.resolvedAt = nowIso();
      store.put(conflict);
      await transactionPromise(tx);

      return this.mutate({
        entityType: conflict.entityType,
        entityId: conflict.entityId,
        action: 'patch',
        changes: resolution.changes || {},
        criticalFields: Object.keys(resolution.changes || {})
      });
    }

    _applyOperation(entity, operation) {
      const result = clone(entity);
      result.data = clone(result.data || {});
      switch (operation.action) {
        case 'patch':
          Object.assign(result.data, clone(operation.changes || {}));
          break;
        case 'replace':
          result.data = clone(operation.changes || {});
          break;
        case 'delete':
          result.deleted = true;
          result.deletedAt = operation.clientCreatedAt;
          break;
        case 'restore':
          result.deleted = false;
          result.deletedAt = null;
          break;
        case 'append': {
          const field = operation.appendField;
          if (!field) throw new Error('appendField is required for append action');
          const list = Array.isArray(result.data[field]) ? result.data[field].slice() : [];
          list.push(clone(operation.appendValue));
          result.data[field] = list;
          break;
        }
        default:
          throw new Error(`Unsupported action: ${operation.action}`);
      }
      return result;
    }

    async _applyRemoteEvents(events) {
      for (const event of events) {
        if (!event || !event.operationId || !event.entity) continue;
        const tx = this.db.transaction(['entities', 'outbox', 'appliedOps', 'meta'], 'readwrite');
        const appliedStore = tx.objectStore('appliedOps');
        const alreadyApplied = await requestPromise(appliedStore.get(event.operationId));
        if (alreadyApplied) {
          const currentSequence = Number((await requestPromise(tx.objectStore('meta').get('lastServerSequence')) || {}).value || 0);
          if (Number(event.serverSequence || 0) > currentSequence) {
            tx.objectStore('meta').put({ key: 'lastServerSequence', value: Number(event.serverSequence) });
          }
          await transactionPromise(tx);
          continue;
        }

        const canonical = event.entity;
        const key = entityKey(canonical.entityType, canonical.entityId);
        const pending = await requestPromise(tx.objectStore('outbox').index('entityKey').getAll(key));
        let local = {
          key,
          entityType: canonical.entityType,
          entityId: canonical.entityId,
          data: clone(canonical.data || {}),
          deleted: Boolean(canonical.deleted),
          deletedAt: canonical.deletedAt || null,
          serverVersion: Number(canonical.version || 0),
          localRevision: 0,
          localUpdatedAt: canonical.updatedAt || nowIso(),
          localUpdatedByDevice: canonical.updatedByDevice || null,
          dirty: false
        };

        const active = pending
          .filter((op) => ACTIVE_OUTBOX_STATUSES.has(op.status))
          .sort((a, b) => String(a.clientCreatedAt).localeCompare(String(b.clientCreatedAt)));
        for (const operation of active) local = this._applyOperation(local, operation);
        local.dirty = active.length > 0;

        tx.objectStore('entities').put(local);
        appliedStore.put({ operationId: event.operationId, serverSequence: Number(event.serverSequence || 0), appliedAt: nowIso() });
        tx.objectStore('meta').put({ key: 'lastServerSequence', value: Number(event.serverSequence || 0) });
        await transactionPromise(tx);
        this.onEntityChanged(clone(local), { source: 'remote', operationId: event.operationId });
      }
      if (events.length) {
        await this._setMeta('lastSyncAt', nowIso());
        await this._setMeta('lastSyncError', null);
      }
    }

    async _applyAcknowledgements(acknowledgements) {
      if (!acknowledgements.length) return;
      const tx = this.db.transaction('outbox', 'readwrite');
      const store = tx.objectStore('outbox');
      for (const ack of acknowledgements) {
        const operation = await requestPromise(store.get(ack.operationId));
        if (!operation) continue;
        operation.status = ack.status === 'conflict' ? 'conflict' : 'acked';
        operation.serverSequence = ack.serverSequence == null ? null : Number(ack.serverSequence);
        operation.serverVersion = ack.entityVersion == null ? null : Number(ack.entityVersion);
        operation.acknowledgedAt = nowIso();
        operation.lastError = ack.error || null;
        store.put(operation);
      }
      await transactionPromise(tx);
    }

    async _applyConflicts(conflicts) {
      if (!conflicts.length) return;
      const tx = this.db.transaction('conflicts', 'readwrite');
      const store = tx.objectStore('conflicts');
      for (const conflict of conflicts) {
        store.put({ ...clone(conflict), status: conflict.status || 'open', receivedAt: nowIso() });
      }
      await transactionPromise(tx);
      conflicts.forEach((conflict) => this.onConflict(clone(conflict)));
    }

    async _getDueOperations(limit) {
      const tx = this.db.transaction('outbox', 'readonly');
      const all = await requestPromise(tx.objectStore('outbox').getAll());
      const now = Date.now();
      return all
        .filter((op) => (op.status === 'pending' || op.status === 'failed') && Number(op.nextAttemptAt || 0) <= now)
        .sort((a, b) => String(a.clientCreatedAt).localeCompare(String(b.clientCreatedAt)))
        .slice(0, limit);
    }

    async _markSending(operationIds) {
      if (!operationIds.length) return;
      const tx = this.db.transaction('outbox', 'readwrite');
      const store = tx.objectStore('outbox');
      for (const id of operationIds) {
        const operation = await requestPromise(store.get(id));
        if (!operation || !['pending', 'failed'].includes(operation.status)) continue;
        operation.status = 'sending';
        operation.sendingAt = nowIso();
        store.put(operation);
      }
      await transactionPromise(tx);
    }

    async _markSendingFailed(error) {
      const tx = this.db.transaction('outbox', 'readwrite');
      const store = tx.objectStore('outbox');
      const all = await requestPromise(store.getAll());
      for (const operation of all.filter((op) => op.status === 'sending')) {
        operation.status = 'failed';
        operation.retryCount = Number(operation.retryCount || 0) + 1;
        operation.nextAttemptAt = Date.now() + retryDelayMs(operation.retryCount);
        operation.lastError = String(error && error.message ? error.message : error);
        store.put(operation);
      }
      await transactionPromise(tx);
      await this._setMeta('lastSyncError', String(error && error.message ? error.message : error));
    }

    async _cleanupAcknowledged() {
      const tx = this.db.transaction(['outbox', 'appliedOps'], 'readwrite');
      const outbox = tx.objectStore('outbox');
      const all = await requestPromise(outbox.getAll());
      const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
      for (const operation of all) {
        if (operation.status === 'acked' && Date.parse(operation.acknowledgedAt || 0) < cutoff) {
          outbox.delete(operation.operationId);
        }
      }
      await transactionPromise(tx);
    }

    _wireOperation(operation) {
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
        clientCreatedAt: operation.clientCreatedAt
      };
    }

    async _post(payload) {
      const controller = new AbortController();
      const timeout = global.setTimeout(() => controller.abort(), this.requestTimeoutMs);
      try {
        const response = await fetch(this.endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'text/plain;charset=utf-8' },
          body: JSON.stringify(payload),
          signal: controller.signal,
          cache: 'no-store'
        });
        if (!response.ok) throw new Error(`Sync HTTP ${response.status}`);
        return await response.json();
      } finally {
        global.clearTimeout(timeout);
      }
    }

    _emitStatus(state, details) {
      Promise.resolve(this.getDiagnostics().catch(() => ({ deviceId: this.deviceId }))).then((diagnostics) => {
        this.onStatus({ state, at: nowIso(), ...details, diagnostics });
      });
    }

    async _getMeta(key) {
      const tx = this.db.transaction('meta', 'readonly');
      const record = await requestPromise(tx.objectStore('meta').get(key));
      return record ? record.value : null;
    }

    async _setMeta(key, value) {
      const tx = this.db.transaction('meta', 'readwrite');
      tx.objectStore('meta').put({ key, value });
      await transactionPromise(tx);
    }

    _openDatabase() {
      return new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, DB_VERSION);
        request.onupgradeneeded = () => {
          const db = request.result;
          if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta', { keyPath: 'key' });
          if (!db.objectStoreNames.contains('entities')) {
            const store = db.createObjectStore('entities', { keyPath: 'key' });
            store.createIndex('entityType', 'entityType', { unique: false });
          }
          if (!db.objectStoreNames.contains('outbox')) {
            const store = db.createObjectStore('outbox', { keyPath: 'operationId' });
            store.createIndex('status', 'status', { unique: false });
            store.createIndex('entityKey', 'entityKey', { unique: false });
            store.createIndex('clientCreatedAt', 'clientCreatedAt', { unique: false });
          }
          if (!db.objectStoreNames.contains('appliedOps')) db.createObjectStore('appliedOps', { keyPath: 'operationId' });
          if (!db.objectStoreNames.contains('conflicts')) db.createObjectStore('conflicts', { keyPath: 'conflictId' });
        };
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error || new Error('Cannot open Sync v2 IndexedDB'));
        request.onblocked = () => reject(new Error('Sync v2 database upgrade is blocked by another app window'));
      });
    }
  }

  global.MoyMirSyncV2 = { SyncV2Engine, PROTOCOL_VERSION };
})(typeof window !== 'undefined' ? window : globalThis);
