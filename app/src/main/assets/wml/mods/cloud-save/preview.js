(() => {
  'use strict';

  const INDEX_SCHEMA = 'wayward-cloud-save-preview-index';
  const PREVIEW_VERSION = 1;

  function cleanText(value) {
    if (typeof value !== 'string') return '';
    return value.replace(/\u0000/g, '').trim();
  }

  function validTimestamp(value) {
    let number = NaN;
    if (typeof value === 'number' && Number.isFinite(value)) {
      number = value;
    } else if (typeof value === 'string' && value.trim()) {
      const text = value.trim();
      number = /^\d{10,13}$/.test(text) ? Number(text) : Date.parse(text);
    }
    return Number.isFinite(number) && number > 0 ? number : null;
  }

  function roleFromCharacter(character, state) {
    const candidates = [
      [character, 'originLabel', true],
      [character, 'backgroundLabel', true],
      [character, 'occupationLabel', true],
      [character, 'roleLabel', true],
      [character, 'socialClassLabel', true],
      [character, 'origin', true],
      [character, 'background', true],
      [character, 'occupation', true],
      [character, 'role', true],
      [character, 'socialClass', true],
      [state, 'originLabel', true],
      [state, 'backgroundLabel', true],
      [state, 'occupationLabel', true],
      [state, 'roleLabel', true]
    ];
    for (const [source, key, appendOrigin] of candidates) {
      if (!source || typeof source !== 'object') continue;
      const value = cleanText(source[key]);
      if (!value) continue;
      if (/(출신|배경)$/.test(value)) return value;
      return appendOrigin ? value + ' 출신' : value;
    }
    // archetypeId is an internal identifier, not a display label. Do not guess
    // a Korean occupation/background from values such as "elena_farmgirl".
    return '';
  }

  function buildSummary(snapshot) {
    const state = snapshot && snapshot.state && typeof snapshot.state === 'object'
      ? snapshot.state : {};
    const characterId = cleanText(state.primaryCharacterId);
    const characters = state.characters && typeof state.characters === 'object'
      ? state.characters : {};
    const character = characterId && characters[characterId] &&
      typeof characters[characterId] === 'object' ? characters[characterId] : {};
    const characterName = cleanText(character.name);
    const dayValue = Number(state.day);
    const day = Number.isFinite(dayValue) && dayValue >= 0 ? dayValue : null;
    const goldValue = Number(state.economy && state.economy.gold);
    const gold = Number.isFinite(goldValue) && goldValue >= 0 ? goldValue : null;
    const role = roleFromCharacter(character, state);
    return {
      characterName,
      day,
      gold,
      role,
      savedAt: validTimestamp(snapshot && snapshot.savedAt)
    };
  }

  function formatSavedAt(value) {
    const timestamp = validTimestamp(value);
    if (timestamp == null) return '';
    const date = new Date(timestamp);
    if (!Number.isFinite(date.getTime())) return '';
    try {
      const parts = new Intl.DateTimeFormat('ko-KR', {
        timeZone: 'Asia/Seoul',
        month: 'numeric',
        day: 'numeric',
        hour: 'numeric',
        minute: '2-digit',
        hour12: true
      }).formatToParts(date);
      const get = type => {
        const part = parts.find(item => item.type === type);
        return part ? part.value : '';
      };
      const month = get('month');
      const day = get('day');
      const period = get('dayPeriod');
      const hour = get('hour');
      const minute = get('minute');
      if (month && day && period && hour && minute) {
        return month + '월 ' + day + '일 ' + period + ' ' + hour + ':' + minute;
      }
    } catch (_) {}
    return '';
  }

  function formatSummary(summary) {
    if (!summary || typeof summary !== 'object') return '';
    const parts = [];
    const name = cleanText(summary.characterName);
    if (name) parts.push(name);
    if (summary.day != null && Number.isFinite(Number(summary.day))) {
      parts.push('진행일: ' + Number(summary.day));
    }
    if (summary.gold != null && Number.isFinite(Number(summary.gold))) {
      parts.push(new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 })
        .format(Number(summary.gold)) + '골드');
    }
    const role = cleanText(summary.role);
    if (role) parts.push(/(출신|배경)$/.test(role) ? role : role + ' 출신');
    const savedAt = formatSavedAt(summary.savedAt);
    if (savedAt) parts.push(savedAt);
    return parts.join(' · ');
  }

  function buildRecord(snapshot, slot, fileId) {
    const id = cleanText(fileId);
    const slotNumber = Number(slot);
    if (!id || !Number.isSafeInteger(slotNumber) || slotNumber < 1) {
      throw new Error('CloudSave 미리보기 식별자가 올바르지 않습니다.');
    }
    return {
      previewVersion: PREVIEW_VERSION,
      fileId: id,
      slot: slotNumber,
      summary: buildSummary(snapshot),
      memo: typeof (snapshot && snapshot.memo) === 'string' ? snapshot.memo : ''
    };
  }

  function emptyIndex() {
    return { previewVersion: PREVIEW_VERSION, entries: Object.create(null) };
  }

  function createIndexSnapshot(index) {
    const safe = index && index.entries && typeof index.entries === 'object'
      ? index.entries : Object.create(null);
    return {
      schema: 'wayward-cloud-save',
      version: 1,
      gameFamily: 'wayward',
      game: 'cloud-save-preview-index',
      gameVersion: 'preview-index-v1',
      nativeFormat: 1,
      savedAt: Date.now(),
      memo: '',
      title: 'CloudSave encrypted preview index',
      state: {},
      cloudSavePreviewIndex: INDEX_SCHEMA,
      previewVersion: PREVIEW_VERSION,
      entries: safe
    };
  }

  function parseIndexSnapshot(snapshot) {
    if (!snapshot || snapshot.schema !== 'wayward-cloud-save' || snapshot.version !== 1 ||
        snapshot.cloudSavePreviewIndex !== INDEX_SCHEMA ||
        snapshot.previewVersion !== PREVIEW_VERSION ||
        !snapshot.entries || typeof snapshot.entries !== 'object' ||
        Array.isArray(snapshot.entries)) {
      throw new Error('CloudSave 미리보기 색인 형식이 올바르지 않습니다.');
    }
    const entries = Object.create(null);
    for (const [key, value] of Object.entries(snapshot.entries)) {
      if (!value || typeof value !== 'object' || value.fileId !== key ||
          !Number.isSafeInteger(Number(value.slot)) || Number(value.slot) < 1 ||
          value.previewVersion !== PREVIEW_VERSION) continue;
      const summary = value.summary && typeof value.summary === 'object'
        ? value.summary : {};
      entries[key] = {
        previewVersion: PREVIEW_VERSION,
        fileId: key,
        slot: Number(value.slot),
        summary: {
          characterName: cleanText(summary.characterName),
          day: summary.day == null || !Number.isFinite(Number(summary.day)) ? null : Number(summary.day),
          gold: summary.gold == null || !Number.isFinite(Number(summary.gold)) ? null : Number(summary.gold),
          role: cleanText(summary.role),
          savedAt: validTimestamp(summary.savedAt)
        },
        memo: typeof value.memo === 'string' ? value.memo : ''
      };
    }
    return { previewVersion: PREVIEW_VERSION, entries };
  }

  function upsert(index, record) {
    const current = index && index.entries && typeof index.entries === 'object'
      ? index.entries : Object.create(null);
    const entries = Object.assign(Object.create(null), current);
    entries[record.fileId] = record;
    return { previewVersion: PREVIEW_VERSION, entries };
  }

  function remove(index, fileId) {
    const current = index && index.entries && typeof index.entries === 'object'
      ? index.entries : Object.create(null);
    const entries = Object.assign(Object.create(null), current);
    delete entries[String(fileId)];
    return { previewVersion: PREVIEW_VERSION, entries };
  }

  globalThis.WMLCloudSavePreview = Object.freeze({
    indexFileName: 'wayward-cloud-slot-preview-index.enc',
    previewVersion: PREVIEW_VERSION,
    buildSummary,
    formatSavedAt,
    formatSummary,
    buildRecord,
    emptyIndex,
    createIndexSnapshot,
    parseIndexSnapshot,
    upsert,
    remove
  });
})();
