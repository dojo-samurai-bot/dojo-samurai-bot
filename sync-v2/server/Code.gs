/*
 * Moy Mir / Nash Mir Sync v2 server for Google Apps Script.
 * Deploy as a Web App. Set optional Script Properties:
 *   SYNC_SPREADSHEET_ID = target Google Spreadsheet ID
 *   SYNC_API_KEY        = shared secret expected in request body
 */

var SYNC_V2 = {
  protocolVersion: 2,
  sheets: {
    meta: 'SyncMeta',
    entities: 'SyncEntities',
    operations: 'SyncOperations',
    conflicts: 'SyncConflicts',
    devices: 'SyncDevices'
  },
  headers: {
    meta: ['key', 'value'],
    entities: [
      'entityKey', 'entityType', 'entityId', 'version', 'deleted', 'deletedAt',
      'dataJson', 'fieldVersionsJson', 'updatedAt', 'updatedByDevice', 'lastSequence'
    ],
    operations: [
      'serverSequence', 'operationId', 'deviceId', 'entityType', 'entityId',
      'action', 'status', 'baseVersion', 'payloadJson', 'receivedAt',
      'entityVersion', 'resultJson'
    ],
    conflicts: [
      'conflictId', 'operationId', 'deviceId', 'entityType', 'entityId',
      'fieldsJson', 'serverEntityJson', 'proposedJson', 'status',
      'createdAt', 'resolvedAt', 'resolutionJson'
    ],
    devices: [
      'deviceId', 'name', 'lastSeenAt', 'lastReceivedSequence',
      'lastSentOperationAt', 'protocolVersion', 'appVersion', 'diagnosticsJson'
    ]
  }
};

function doGet() {
  try {
    var context = openSyncContext_();
    return jsonOutput_({
      ok: true,
      service: 'moy-mir-sync-v2',
      protocolVersion: SYNC_V2.protocolVersion,
      currentSequence: getCurrentSequence_(context),
      serverTime: new Date().toISOString()
    });
  } catch (error) {
    return jsonOutput_({ ok: false, error: errorMessage_(error) });
  }
}

function doPost(event) {
  var lock = LockService.getScriptLock();
  try {
    var request = parseRequest_(event);
    verifyApiKey_(request);
    if (request.action !== 'sync') throw new Error('Unsupported action');
    if (Number(request.protocolVersion) !== SYNC_V2.protocolVersion) {
      throw new Error('Unsupported protocol version');
    }

    lock.waitLock(30000);
    var context = openSyncContext_();
    var response = synchronize_(context, request);
    return jsonOutput_(response);
  } catch (error) {
    return jsonOutput_({
      ok: false,
      protocolVersion: SYNC_V2.protocolVersion,
      error: errorMessage_(error),
      serverTime: new Date().toISOString()
    });
  } finally {
    try {
      if (lock.hasLock()) lock.releaseLock();
    } catch (ignored) {}
  }
}

function synchronize_(context, request) {
  var deviceId = requireId_(request.deviceId, 'deviceId');
  var operations = Array.isArray(request.operations) ? request.operations : [];
  if (operations.length > 200) throw new Error('Too many operations in one request');

  var acknowledgements = [];
  var conflicts = [];
  var lastSentAt = null;

  for (var i = 0; i < operations.length; i += 1) {
    var processed = processOperation_(context, operations[i], deviceId);
    acknowledgements.push(processed.acknowledgement);
    if (processed.conflict) conflicts.push(processed.conflict);
    lastSentAt = processed.receivedAt;
  }

  var afterSequence = Math.max(0, Number(request.afterSequence || 0));
  var maxEvents = Math.max(1, Math.min(500, Number(request.maxEvents || 250)));
  var eventPage = readEventsAfter_(context, afterSequence, maxEvents);

  upsertDevice_(context, {
    deviceId: deviceId,
    name: request.deviceName || '',
    lastSeenAt: new Date().toISOString(),
    lastReceivedSequence: eventPage.lastSequence,
    lastSentOperationAt: lastSentAt || '',
    protocolVersion: Number(request.protocolVersion || 0),
    appVersion: request.appVersion || '',
    diagnosticsJson: JSON.stringify(request.diagnostics || {})
  });

  return {
    ok: true,
    protocolVersion: SYNC_V2.protocolVersion,
    acknowledgements: acknowledgements,
    conflicts: conflicts,
    events: eventPage.events,
    hasMore: eventPage.hasMore,
    currentSequence: getCurrentSequence_(context),
    serverTime: new Date().toISOString()
  };
}

function processOperation_(context, rawOperation, requestDeviceId) {
  var operation = normalizeOperation_(rawOperation, requestDeviceId);
  var receivedAt = new Date().toISOString();
  var existingOperation = context.operationsById[operation.operationId];

  if (existingOperation) {
    var existingPayload = existingOperation.payloadJson || '';
    var incomingPayload = JSON.stringify(operation);
    if (existingPayload && existingPayload !== incomingPayload) {
      throw new Error('Duplicate operationId with different payload: ' + operation.operationId);
    }
    var previousResult = safeParseJson_(existingOperation.resultJson, {});
    return {
      receivedAt: receivedAt,
      acknowledgement: previousResult.acknowledgement || {
        operationId: operation.operationId,
        status: existingOperation.status,
        serverSequence: numberOrNull_(existingOperation.serverSequence),
        entityVersion: numberOrNull_(existingOperation.entityVersion)
      },
      conflict: previousResult.conflict || null
    };
  }

  var key = operation.entityType + ':' + operation.entityId;
  var entityRecord = context.entitiesByKey[key] || newEntityRecord_(operation);
  var originalEntity = entitySnapshot_(entityRecord);
  var conflictFields = findConflictFields_(entityRecord, operation);
  var safeChanges = {};
  var changeKeys = Object.keys(operation.changes || {});

  for (var i = 0; i < changeKeys.length; i += 1) {
    var field = changeKeys[i];
    if (conflictFields.indexOf(field) === -1) safeChanges[field] = operation.changes[field];
  }

  var actionConflict = false;
  if ((operation.action === 'delete' || operation.action === 'restore' || operation.action === 'replace') &&
      Number(entityRecord.version || 0) > Number(operation.baseVersion || 0)) {
    actionConflict = true;
  }

  var applied = false;
  var status = 'accepted';
  var conflict = null;
  var sequence = null;

  if (actionConflict) {
    conflictFields = ['__' + operation.action + '__'];
  } else {
    applied = applyOperation_(entityRecord, operation, safeChanges);
  }

  if (conflictFields.length) {
    conflict = createConflict_(context, operation, originalEntity, conflictFields, receivedAt);
    status = applied ? 'partial_conflict' : 'conflict';
  } else if (!applied) {
    status = 'accepted_noop';
  }

  if (applied) {
    sequence = nextSequence_(context);
    entityRecord.version = Number(entityRecord.version || 0) + 1;
    entityRecord.updatedAt = receivedAt;
    entityRecord.updatedByDevice = operation.deviceId;
    entityRecord.lastSequence = sequence;
    updateFieldVersions_(entityRecord, operation, safeChanges);
    upsertEntity_(context, entityRecord);
  }

  var snapshot = entitySnapshot_(entityRecord);
  var acknowledgement = {
    operationId: operation.operationId,
    status: status,
    serverSequence: sequence,
    entityVersion: Number(entityRecord.version || 0),
    error: null
  };
  var result = {
    acknowledgement: acknowledgement,
    conflict: conflict,
    entity: snapshot
  };

  appendOperation_(context, {
    serverSequence: sequence == null ? '' : sequence,
    operationId: operation.operationId,
    deviceId: operation.deviceId,
    entityType: operation.entityType,
    entityId: operation.entityId,
    action: operation.action,
    status: status,
    baseVersion: operation.baseVersion,
    payloadJson: JSON.stringify(operation),
    receivedAt: receivedAt,
    entityVersion: Number(entityRecord.version || 0),
    resultJson: JSON.stringify(result)
  });

  return {
    receivedAt: receivedAt,
    acknowledgement: acknowledgement,
    conflict: conflict
  };
}

function applyOperation_(entityRecord, operation, safeChanges) {
  var applied = false;
  var data = entityRecord.data || {};
  var fields;
  var i;

  switch (operation.action) {
    case 'patch':
      fields = Object.keys(safeChanges);
      for (i = 0; i < fields.length; i += 1) {
        data[fields[i]] = safeChanges[fields[i]];
        applied = true;
      }
      break;

    case 'replace':
      entityRecord.data = cloneJson_(operation.changes || {});
      applied = true;
      break;

    case 'append':
      if (!operation.appendField) throw new Error('appendField is required');
      var list = Array.isArray(data[operation.appendField]) ? data[operation.appendField] : [];
      list.push(cloneJson_(operation.appendValue));
      data[operation.appendField] = list;
      applied = true;
      break;

    case 'delete':
      if (!entityRecord.deleted) {
        entityRecord.deleted = true;
        entityRecord.deletedAt = operation.clientCreatedAt || new Date().toISOString();
        applied = true;
      }
      break;

    case 'restore':
      if (entityRecord.deleted) {
        entityRecord.deleted = false;
        entityRecord.deletedAt = '';
        applied = true;
      }
      break;

    default:
      throw new Error('Unsupported operation action: ' + operation.action);
  }

  entityRecord.data = data;
  return applied;
}

function findConflictFields_(entityRecord, operation) {
  if (operation.action !== 'patch') return [];
  var fieldVersions = entityRecord.fieldVersions || {};
  var critical = operation.criticalFields || [];
  var conflicts = [];
  for (var i = 0; i < critical.length; i += 1) {
    var field = critical[i];
    if (Number(fieldVersions[field] || 0) > Number(operation.baseVersion || 0)) {
      conflicts.push(field);
    }
  }
  return conflicts;
}

function updateFieldVersions_(entityRecord, operation, safeChanges) {
  var nextVersion = Number(entityRecord.version || 0);
  var fieldVersions = entityRecord.fieldVersions || {};
  var fields = [];

  if (operation.action === 'patch') fields = Object.keys(safeChanges);
  if (operation.action === 'replace') fields = Object.keys(operation.changes || {});
  if (operation.action === 'append') fields = [operation.appendField];
  if (operation.action === 'delete' || operation.action === 'restore') fields = ['__deleted__'];

  for (var i = 0; i < fields.length; i += 1) {
    if (fields[i]) fieldVersions[fields[i]] = nextVersion;
  }
  entityRecord.fieldVersions = fieldVersions;
}

function createConflict_(context, operation, serverEntity, fields, createdAt) {
  var conflictId = Utilities.getUuid();
  var proposed = {
    action: operation.action,
    changes: operation.changes || {},
    appendField: operation.appendField || null,
    appendValue: operation.appendValue,
    baseVersion: operation.baseVersion,
    clientCreatedAt: operation.clientCreatedAt
  };
  var conflict = {
    conflictId: conflictId,
    operationId: operation.operationId,
    deviceId: operation.deviceId,
    entityType: operation.entityType,
    entityId: operation.entityId,
    fields: fields,
    serverEntity: serverEntity,
    proposed: proposed,
    status: 'open',
    createdAt: createdAt
  };

  context.sheets.conflicts.appendRow([
    conflictId,
    operation.operationId,
    operation.deviceId,
    operation.entityType,
    operation.entityId,
    JSON.stringify(fields),
    JSON.stringify(serverEntity),
    JSON.stringify(proposed),
    'open',
    createdAt,
    '',
    ''
  ]);
  return conflict;
}

function readEventsAfter_(context, afterSequence, limit) {
  var rows = context.operationRows
    .filter(function (row) {
      return Number(row.serverSequence || 0) > afterSequence &&
        (row.status === 'accepted' || row.status === 'partial_conflict');
    })
    .sort(function (a, b) { return Number(a.serverSequence) - Number(b.serverSequence); });

  var selected = rows.slice(0, limit);
  var events = [];
  for (var i = 0; i < selected.length; i += 1) {
    var result = safeParseJson_(selected[i].resultJson, {});
    if (!result.entity) continue;
    events.push({
      serverSequence: Number(selected[i].serverSequence),
      operationId: selected[i].operationId,
      deviceId: selected[i].deviceId,
      entityType: selected[i].entityType,
      entityId: selected[i].entityId,
      action: selected[i].action,
      entity: result.entity,
      receivedAt: selected[i].receivedAt
    });
  }

  return {
    events: events,
    hasMore: rows.length > selected.length,
    lastSequence: events.length ? events[events.length - 1].serverSequence : afterSequence
  };
}

function normalizeOperation_(raw, requestDeviceId) {
  if (!raw || typeof raw !== 'object') throw new Error('Invalid operation');
  var operationId = requireId_(raw.operationId, 'operationId');
  var entityType = requireToken_(raw.entityType, 'entityType');
  var entityId = requireId_(raw.entityId, 'entityId');
  var deviceId = requireId_(raw.deviceId || requestDeviceId, 'operation.deviceId');
  if (deviceId !== requestDeviceId) throw new Error('Operation deviceId mismatch');
  if (Number(raw.protocolVersion || SYNC_V2.protocolVersion) !== SYNC_V2.protocolVersion) {
    throw new Error('Operation protocol version mismatch');
  }

  return {
    operationId: operationId,
    protocolVersion: SYNC_V2.protocolVersion,
    schemaVersion: Math.max(1, Number(raw.schemaVersion || 2)),
    deviceId: deviceId,
    entityType: entityType,
    entityId: entityId,
    action: String(raw.action || 'patch'),
    changes: raw.changes && typeof raw.changes === 'object' ? cloneJson_(raw.changes) : {},
    appendField: raw.appendField ? String(raw.appendField) : null,
    appendValue: cloneJson_(raw.appendValue),
    criticalFields: Array.isArray(raw.criticalFields) ? raw.criticalFields.map(String) : [],
    baseVersion: Math.max(0, Number(raw.baseVersion || 0)),
    clientCreatedAt: raw.clientCreatedAt || new Date().toISOString()
  };
}

function newEntityRecord_(operation) {
  return {
    rowNumber: null,
    entityKey: operation.entityType + ':' + operation.entityId,
    entityType: operation.entityType,
    entityId: operation.entityId,
    version: 0,
    deleted: false,
    deletedAt: '',
    data: {},
    fieldVersions: {},
    updatedAt: '',
    updatedByDevice: '',
    lastSequence: 0
  };
}

function entitySnapshot_(record) {
  return {
    entityType: record.entityType,
    entityId: record.entityId,
    version: Number(record.version || 0),
    deleted: Boolean(record.deleted),
    deletedAt: record.deletedAt || null,
    data: cloneJson_(record.data || {}),
    updatedAt: record.updatedAt || null,
    updatedByDevice: record.updatedByDevice || null,
    lastSequence: Number(record.lastSequence || 0)
  };
}

function upsertEntity_(context, record) {
  var values = [[
    record.entityKey,
    record.entityType,
    record.entityId,
    Number(record.version || 0),
    Boolean(record.deleted),
    record.deletedAt || '',
    JSON.stringify(record.data || {}),
    JSON.stringify(record.fieldVersions || {}),
    record.updatedAt || '',
    record.updatedByDevice || '',
    Number(record.lastSequence || 0)
  ]];

  if (record.rowNumber) {
    context.sheets.entities.getRange(record.rowNumber, 1, 1, values[0].length).setValues(values);
  } else {
    context.sheets.entities.appendRow(values[0]);
    record.rowNumber = context.sheets.entities.getLastRow();
  }
  context.entitiesByKey[record.entityKey] = record;
}

function appendOperation_(context, operation) {
  context.sheets.operations.appendRow([
    operation.serverSequence,
    operation.operationId,
    operation.deviceId,
    operation.entityType,
    operation.entityId,
    operation.action,
    operation.status,
    operation.baseVersion,
    operation.payloadJson,
    operation.receivedAt,
    operation.entityVersion,
    operation.resultJson
  ]);

  var row = cloneJson_(operation);
  context.operationsById[operation.operationId] = row;
  context.operationRows.push(row);
}

function upsertDevice_(context, device) {
  var existing = context.devicesById[device.deviceId];
  var values = [[
    device.deviceId,
    device.name,
    device.lastSeenAt,
    Number(device.lastReceivedSequence || 0),
    device.lastSentOperationAt,
    Number(device.protocolVersion || 0),
    device.appVersion,
    device.diagnosticsJson
  ]];

  if (existing && existing.rowNumber) {
    context.sheets.devices.getRange(existing.rowNumber, 1, 1, values[0].length).setValues(values);
  } else {
    context.sheets.devices.appendRow(values[0]);
  }
}

function nextSequence_(context) {
  var next = getCurrentSequence_(context) + 1;
  setMeta_(context, 'currentSequence', next);
  context.currentSequence = next;
  return next;
}

function getCurrentSequence_(context) {
  if (context.currentSequence != null) return Number(context.currentSequence || 0);
  context.currentSequence = Number(context.meta.currentSequence || 0);
  return context.currentSequence;
}

function setMeta_(context, key, value) {
  var rowNumber = context.metaRows[key];
  if (rowNumber) {
    context.sheets.meta.getRange(rowNumber, 2).setValue(String(value));
  } else {
    context.sheets.meta.appendRow([key, String(value)]);
    context.metaRows[key] = context.sheets.meta.getLastRow();
  }
  context.meta[key] = String(value);
}

function openSyncContext_() {
  var properties = PropertiesService.getScriptProperties();
  var spreadsheetId = properties.getProperty('SYNC_SPREADSHEET_ID');
  var spreadsheet = spreadsheetId ? SpreadsheetApp.openById(spreadsheetId) : SpreadsheetApp.getActiveSpreadsheet();
  if (!spreadsheet) throw new Error('No spreadsheet configured');

  var sheets = {
    meta: ensureSheet_(spreadsheet, SYNC_V2.sheets.meta, SYNC_V2.headers.meta),
    entities: ensureSheet_(spreadsheet, SYNC_V2.sheets.entities, SYNC_V2.headers.entities),
    operations: ensureSheet_(spreadsheet, SYNC_V2.sheets.operations, SYNC_V2.headers.operations),
    conflicts: ensureSheet_(spreadsheet, SYNC_V2.sheets.conflicts, SYNC_V2.headers.conflicts),
    devices: ensureSheet_(spreadsheet, SYNC_V2.sheets.devices, SYNC_V2.headers.devices)
  };

  var metaRows = rowsAsObjects_(sheets.meta, SYNC_V2.headers.meta);
  var entityRows = rowsAsObjects_(sheets.entities, SYNC_V2.headers.entities);
  var operationRows = rowsAsObjects_(sheets.operations, SYNC_V2.headers.operations);
  var deviceRows = rowsAsObjects_(sheets.devices, SYNC_V2.headers.devices);
  var meta = {};
  var metaRowNumbers = {};
  var entitiesByKey = {};
  var operationsById = {};
  var devicesById = {};

  metaRows.forEach(function (row) {
    meta[row.key] = row.value;
    metaRowNumbers[row.key] = row.rowNumber;
  });
  entityRows.forEach(function (row) {
    row.version = Number(row.version || 0);
    row.deleted = row.deleted === true || String(row.deleted).toLowerCase() === 'true';
    row.data = safeParseJson_(row.dataJson, {});
    row.fieldVersions = safeParseJson_(row.fieldVersionsJson, {});
    row.lastSequence = Number(row.lastSequence || 0);
    entitiesByKey[row.entityKey] = row;
  });
  operationRows.forEach(function (row) {
    operationsById[row.operationId] = row;
  });
  deviceRows.forEach(function (row) {
    devicesById[row.deviceId] = row;
  });

  return {
    spreadsheet: spreadsheet,
    sheets: sheets,
    meta: meta,
    metaRows: metaRowNumbers,
    entitiesByKey: entitiesByKey,
    operationsById: operationsById,
    operationRows: operationRows,
    devicesById: devicesById,
    currentSequence: null
  };
}

function ensureSheet_(spreadsheet, name, headers) {
  var sheet = spreadsheet.getSheetByName(name);
  if (!sheet) sheet = spreadsheet.insertSheet(name);
  if (sheet.getLastRow() === 0) {
    sheet.getRange(1, 1, 1, headers.length).setValues([headers]);
    sheet.setFrozenRows(1);
  } else {
    var actual = sheet.getRange(1, 1, 1, headers.length).getValues()[0];
    for (var i = 0; i < headers.length; i += 1) {
      if (String(actual[i] || '') !== headers[i]) {
        throw new Error('Invalid headers in sheet ' + name);
      }
    }
  }
  return sheet;
}

function rowsAsObjects_(sheet, headers) {
  var lastRow = sheet.getLastRow();
  if (lastRow < 2) return [];
  var values = sheet.getRange(2, 1, lastRow - 1, headers.length).getValues();
  return values.map(function (row, index) {
    var result = { rowNumber: index + 2 };
    for (var i = 0; i < headers.length; i += 1) result[headers[i]] = row[i];
    return result;
  });
}

function parseRequest_(event) {
  if (!event || !event.postData || !event.postData.contents) throw new Error('Empty request body');
  var request = JSON.parse(event.postData.contents);
  if (!request || typeof request !== 'object') throw new Error('Invalid JSON request');
  return request;
}

function verifyApiKey_(request) {
  var expected = PropertiesService.getScriptProperties().getProperty('SYNC_API_KEY');
  if (expected && request.apiKey !== expected) throw new Error('Unauthorized');
}

function requireId_(value, fieldName) {
  var text = String(value || '').trim();
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/.test(text)) {
    throw new Error('Invalid ' + fieldName);
  }
  return text;
}

function requireToken_(value, fieldName) {
  var text = String(value || '').trim();
  if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(text)) throw new Error('Invalid ' + fieldName);
  return text;
}

function safeParseJson_(text, fallback) {
  if (text == null || text === '') return cloneJson_(fallback);
  try {
    return JSON.parse(String(text));
  } catch (ignored) {
    return cloneJson_(fallback);
  }
}

function cloneJson_(value) {
  if (value === undefined) return null;
  return JSON.parse(JSON.stringify(value));
}

function numberOrNull_(value) {
  return value === '' || value == null ? null : Number(value);
}

function jsonOutput_(payload) {
  return ContentService.createTextOutput(JSON.stringify(payload))
    .setMimeType(ContentService.MimeType.JSON);
}

function errorMessage_(error) {
  return String(error && error.message ? error.message : error);
}
