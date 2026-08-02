(function () {
  'use strict';

  const DB_NAME = 'dacha-fibonacci-db';
  const DB_VERSION = 2;
  const DATA_STORES = ['tasks', 'shopping', 'bookings', 'transactions', 'settings'];
  const INTERNAL_STORES = ['syncOutbox', 'syncMeta', 'syncAppliedOps', 'syncConflicts'];
  const STORES = [...DATA_STORES, ...INTERNAL_STORES];
  let dbPromise;

  function assertStore(storeName) {
    if (!STORES.includes(storeName)) throw new Error(`Неизвестный раздел базы: ${storeName}`);
  }

  function assertDataStore(storeName) {
    if (!DATA_STORES.includes(storeName)) throw new Error(`Раздел нельзя синхронизировать: ${storeName}`);
  }

  function assertRecord(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Запись должна быть объектом');
    if (!String(value.id || '').trim()) throw new Error('У записи отсутствует идентификатор');
  }

  function requestPromise(request) {
    return new Promise((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error('Ошибка локальной базы'));
    });
  }

  function transactionPromise(tx) {
    return new Promise((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error || new Error('Ошибка локальной базы'));
      tx.onabort = () => reject(tx.error || new Error('Операция с базой отменена'));
    });
  }

  function openDB() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        for (const store of DATA_STORES) {
          if (!db.objectStoreNames.contains(store)) db.createObjectStore(store, { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains('syncOutbox')) {
          const store = db.createObjectStore('syncOutbox', { keyPath: 'operationId' });
          store.createIndex('status', 'status', { unique: false });
          store.createIndex('entityKey', 'entityKey', { unique: false });
          store.createIndex('clientCreatedAt', 'clientCreatedAt', { unique: false });
        }
        if (!db.objectStoreNames.contains('syncMeta')) db.createObjectStore('syncMeta', { keyPath: 'key' });
        if (!db.objectStoreNames.contains('syncAppliedOps')) db.createObjectStore('syncAppliedOps', { keyPath: 'operationId' });
        if (!db.objectStoreNames.contains('syncConflicts')) db.createObjectStore('syncConflicts', { keyPath: 'conflictId' });
      };
      request.onsuccess = () => {
        request.result.onversionchange = () => request.result.close();
        resolve(request.result);
      };
      request.onerror = () => reject(request.error || new Error('Не удалось открыть локальную базу'));
      request.onblocked = () => reject(new Error('База занята другой вкладкой. Закрой приложение и открой снова.'));
    });
    return dbPromise;
  }

  async function runTransaction(storeNames, mode, operation) {
    const names = Array.isArray(storeNames) ? storeNames : [storeNames];
    names.forEach(assertStore);
    const db = await openDB();
    const tx = db.transaction(names, mode);
    const stores = Object.fromEntries(names.map(name => [name, tx.objectStore(name)]));
    const result = await operation(stores, tx);
    await transactionPromise(tx);
    return result;
  }

  async function getAll(storeName) {
    assertStore(storeName);
    const db = await openDB();
    return requestPromise(db.transaction(storeName, 'readonly').objectStore(storeName).getAll());
  }

  async function get(storeName, id) {
    assertStore(storeName);
    if (!id) return null;
    const db = await openDB();
    return (await requestPromise(db.transaction(storeName, 'readonly').objectStore(storeName).get(id))) || null;
  }

  async function put(storeName, value) {
    assertStore(storeName);
    if (DATA_STORES.includes(storeName)) assertRecord(value);
    await runTransaction(storeName, 'readwrite', async stores => { stores[storeName].put(value); });
    return value;
  }

  async function remove(storeName, id) {
    assertStore(storeName);
    if (!id) return;
    await runTransaction(storeName, 'readwrite', async stores => { stores[storeName].delete(id); });
  }

  async function clear(storeName) {
    assertStore(storeName);
    await runTransaction(storeName, 'readwrite', async stores => { stores[storeName].clear(); });
  }

  async function putWithOperation(storeName, value, operation) {
    assertDataStore(storeName);
    assertRecord(value);
    if (!operation || !operation.operationId) throw new Error('У операции отсутствует operationId');
    await runTransaction([storeName, 'syncOutbox'], 'readwrite', async stores => {
      stores[storeName].put(value);
      stores.syncOutbox.put(operation);
    });
    return value;
  }

  async function removeWithOperation(storeName, id, operation) {
    assertDataStore(storeName);
    if (!id) return;
    if (!operation || !operation.operationId) throw new Error('У операции отсутствует operationId');
    await runTransaction([storeName, 'syncOutbox'], 'readwrite', async stores => {
      stores[storeName].delete(id);
      stores.syncOutbox.put(operation);
    });
  }

  async function getMeta(key, fallback = null) {
    const record = await get('syncMeta', key);
    return record ? record.value : fallback;
  }

  async function setMeta(key, value) {
    await put('syncMeta', { key, value });
    return value;
  }

  async function updateOutbox(operationId, updater) {
    return runTransaction('syncOutbox', 'readwrite', async stores => {
      const current = await requestPromise(stores.syncOutbox.get(operationId));
      if (!current) return null;
      const updated = updater({ ...current });
      if (updated) stores.syncOutbox.put(updated);
      else stores.syncOutbox.delete(operationId);
      return updated || null;
    });
  }

  async function getOperationsForEntity(entityKey) {
    const db = await openDB();
    const tx = db.transaction('syncOutbox', 'readonly');
    return requestPromise(tx.objectStore('syncOutbox').index('entityKey').getAll(entityKey));
  }

  async function applyRemoteEntity(event, rebasedRecord, pendingCount) {
    const storeName = event.entity.entityType;
    assertDataStore(storeName);
    const entityId = event.entity.entityId;
    const entityVersionKey = `entityVersion:${storeName}:${entityId}`;
    await runTransaction([storeName, 'syncMeta', 'syncAppliedOps'], 'readwrite', async stores => {
      if (event.entity.deleted || !rebasedRecord) stores[storeName].delete(entityId);
      else stores[storeName].put(rebasedRecord);
      stores.syncMeta.put({ key: entityVersionKey, value: Number(event.entity.version || 0) });
      stores.syncMeta.put({ key: 'lastServerSequence', value: Number(event.serverSequence || 0) });
      stores.syncAppliedOps.put({
        operationId: event.operationId,
        serverSequence: Number(event.serverSequence || 0),
        appliedAt: new Date().toISOString(),
        pendingCount: Number(pendingCount || 0),
      });
    });
  }

  async function storeConflict(conflict) {
    if (!conflict || !conflict.conflictId) return;
    await put('syncConflicts', { ...conflict, receivedAt: new Date().toISOString(), status: conflict.status || 'open' });
  }

  async function exportAll() {
    const output = { version: 1, app: 'dacha-fibonacci', exportedAt: new Date().toISOString(), data: {} };
    for (const store of DATA_STORES) output.data[store] = await getAll(store);
    return output;
  }

  function validatePayload(payload) {
    if (!payload || payload.version !== 1 || !payload.data || typeof payload.data !== 'object') throw new Error('Неподдерживаемый формат копии');
    for (const store of DATA_STORES) {
      const records = payload.data[store] || [];
      if (!Array.isArray(records)) throw new Error(`Повреждён раздел копии: ${store}`);
      for (const record of records) assertRecord(record);
    }
  }

  async function replaceAll(payload) {
    for (const store of DATA_STORES) {
      await clear(store);
      for (const item of payload.data[store] || []) await put(store, item);
    }
  }

  async function importAll(payload) {
    validatePayload(payload);
    const previous = await exportAll();
    try {
      await replaceAll(payload);
    } catch (error) {
      try { await replaceAll(previous); } catch (_) { }
      throw new Error(`Импорт не завершён: ${error.message || 'ошибка базы'}`);
    }
  }

  window.DachaDB = {
    STORES,
    DATA_STORES,
    getAll,
    get,
    put,
    remove,
    clear,
    runTransaction,
    putWithOperation,
    removeWithOperation,
    getMeta,
    setMeta,
    updateOutbox,
    getOperationsForEntity,
    applyRemoteEntity,
    storeConflict,
    exportAll,
    importAll,
  };
})();
