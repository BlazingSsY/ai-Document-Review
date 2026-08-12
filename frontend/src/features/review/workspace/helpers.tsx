// 纯数据/渲染辅助：从 ReviewWorkspacePage 抽出，无组件状态，便于复用与单测。

export function extractIssues(aiResult: Record<string, unknown> | null): Array<Record<string, unknown>> {
  if (!aiResult) return [];
  const allIssues = aiResult.allIssues;
  if (Array.isArray(allIssues)) return allIssues;
  return [];
}

export function extractCheckResults(aiResult: Record<string, unknown> | null): Array<Record<string, unknown>> {
  if (!aiResult) return [];
  const allCheckResults = aiResult.allCheckResults;
  if (Array.isArray(allCheckResults)) return allCheckResults;
  return [];
}

export function normalizeStatus(status: string): string {
  return status?.toLowerCase() || 'pending';
}

export function formatTime(date: Date): string {
  return date.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export const CHECK_STATUS_LABELS: Record<string, string> = {
  Pass: '通过',
  Partial: '部分通过',
  Fail: '不通过',
  'N/A': '不适用',
  Review: '待复核',
};

export function textField(record: Record<string, unknown> | null | undefined, keys: string[]): string {
  if (!record) return '';
  for (const key of keys) {
    const value = record[key];
    if (value !== undefined && value !== null && String(value).trim()) {
      return String(value);
    }
  }
  return '';
}

export function numericField(record: Record<string, unknown> | null | undefined, keys: string[]): number | undefined {
  if (!record) return undefined;
  for (const key of keys) {
    const value = Number(record[key]);
    if (Number.isFinite(value) && value > 0) return value;
  }
  return undefined;
}

export function scoreField(record: Record<string, unknown> | null | undefined, keys: string[]): number | undefined {
  if (!record) return undefined;
  for (const key of keys) {
    const value = Number(record[key]);
    if (Number.isFinite(value)) return value;
  }
  return undefined;
}

export function sourcePayload(chunk: Record<string, unknown> | undefined): Record<string, unknown> | null {
  if (!chunk) return null;
  const source = chunk.source;
  if (source && typeof source === 'object' && !Array.isArray(source)) {
    return source as Record<string, unknown>;
  }
  return chunk;
}

export function sourceText(chunk: Record<string, unknown> | undefined): string {
  const source = sourcePayload(chunk);
  return textField(source, ['text', 'content', 'originalText', 'sourceText']);
}

export function sourceTitle(chunk: Record<string, unknown> | undefined): string {
  const source = sourcePayload(chunk);
  return textField(source, ['sectionPath', 'chapterTitle', 'title']) || '原文片段';
}

export function recordArray(value: unknown): Array<Record<string, unknown>> {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is Record<string, unknown> => (
    !!item && typeof item === 'object' && !Array.isArray(item)
  ));
}

interface DocumentPosition {
  chunk: number;
  chapter: number;
  node: number;
  section: number[];
  originalIndex: number;
}

const UNKNOWN_DOCUMENT_POSITION = Number.MAX_SAFE_INTEGER;

function sourceIdPosition(value: string): { chapter?: number; node?: number } {
  const match = value.match(/(?:^|-)C(\d+)(?:-N(\d+))?/i);
  if (!match) return {};
  return {
    chapter: Number(match[1]),
    node: match[2] ? Number(match[2]) : undefined,
  };
}

function sourceIdChunk(value: string): number | undefined {
  const match = value.match(/^CHUNK-(\d+)$/i);
  return match ? Number(match[1]) : undefined;
}

function sectionNumberPath(value: string): number[] {
  const matches = value.match(/\d+(?:[.．]\d+)*/g);
  const deepestSection = matches?.[matches.length - 1];
  return deepestSection
    ? deepestSection.split(/[.．]/).map(Number).filter(Number.isFinite)
    : [];
}

function compareNumberPaths(left: number[], right: number[]): number {
  const length = Math.max(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    const leftPart = left[index] ?? -1;
    const rightPart = right[index] ?? -1;
    if (leftPart !== rightPart) return leftPart - rightPart;
  }
  return 0;
}

function itemDocumentPosition(item: Record<string, unknown>, originalIndex: number): DocumentPosition {
  const refs = recordArray(item.sourceRefs);
  // matched_chunk carries the exact node hit. Other references can be supporting
  // chapters and must not move an item away from its actual finding location.
  const primaryRef = refs.find((ref) => textField(ref, ['reason']) === 'matched_chunk')
    ?? refs.find((ref) => textField(ref, ['startNodeId', 'start_node_id']))
    ?? refs[0];

  const sourceId = textField(primaryRef, ['sourceId', 'blockId']);
  const startNodeId = textField(item, ['startNodeId', 'start_node_id'])
    || textField(primaryRef, ['startNodeId', 'start_node_id']);
  const sourcePosition = sourceIdPosition(startNodeId || sourceId);
  const chunk = numericField(item, ['sourceChunk', 'chunk', 'chunkNo', 'chunkIndex'])
    ?? numericField(primaryRef, ['chunk', 'sourceChunk'])
    ?? sourceIdChunk(sourceId)
    ?? UNKNOWN_DOCUMENT_POSITION;
  const chapter = numericField(item, ['chapterIndex'])
    ?? numericField(primaryRef, ['chapterIndex'])
    ?? sourcePosition.chapter
    ?? UNKNOWN_DOCUMENT_POSITION;
  const node = sourcePosition.node ?? UNKNOWN_DOCUMENT_POSITION;
  const section = sectionNumberPath(
    textField(primaryRef, ['sectionPath', 'title', 'sourceTitle'])
      || textField(item, ['sectionPath', 'sourceTitle', 'chapterTitle', 'location']),
  );

  return { chunk, chapter, node, section, originalIndex };
}

/**
 * Return review items in the order their primary evidence appears in the source
 * document. The final original-index tie breaker keeps the ordering deterministic
 * for several rules that point at the same paragraph or for document-level checks
 * that do not have one precise source node.
 */
export function sortReviewItemsByDocumentOrder(
  items: Array<Record<string, unknown>>,
): Array<Record<string, unknown>> {
  return items
    .map((item, originalIndex) => ({ item, position: itemDocumentPosition(item, originalIndex) }))
    .sort((left, right) => {
      if (left.position.chunk !== right.position.chunk) {
        return left.position.chunk - right.position.chunk;
      }
      if (left.position.chapter !== right.position.chapter) {
        return left.position.chapter - right.position.chapter;
      }
      if (left.position.node !== right.position.node) {
        return left.position.node - right.position.node;
      }
      const sectionOrder = compareNumberPaths(left.position.section, right.position.section);
      return sectionOrder || left.position.originalIndex - right.position.originalIndex;
    })
    .map(({ item }) => item);
}

export function sourceRefKey(ref: Record<string, unknown>): string {
  const sourceId = textField(ref, ['sourceId', 'blockId']);
  if (sourceId) return `id:${sourceId}`;
  const chapterIndex = numericField(ref, ['chapterIndex']);
  if (chapterIndex !== undefined) return `chapter:${chapterIndex}`;
  const chunk = numericField(ref, ['chunk', 'sourceChunk']);
  if (chunk !== undefined) return `chunk:${chunk}`;
  return textField(ref, ['sectionPath', 'title', 'sourceTitle']);
}

export function sourceCandidateKey(source: Record<string, unknown>): string {
  const sourceId = textField(source, ['sourceId', 'blockId']);
  if (sourceId) return `id:${sourceId}`;
  const chapterIndex = numericField(source, ['chapterIndex']);
  if (chapterIndex !== undefined) return `chapter:${chapterIndex}`;
  const chunk = numericField(source, ['chunk', 'sourceChunk']);
  if (chunk !== undefined) return `chunk:${chunk}`;
  return textField(source, ['sectionPath', 'chapterTitle', 'title']);
}

export function sourceRefsForItem(
  item: Record<string, unknown> | null,
  chunk: Record<string, unknown> | undefined,
): Array<Record<string, unknown>> {
  const refs = [
    ...recordArray(item?.sourceRefs),
    ...recordArray(chunk?.sourceRefs),
  ];
  const chunkNo = numericField(item, ['sourceChunk', 'chunk'])
    ?? numericField(chunk, ['chunk', 'sourceChunk']);
  if (chunkNo !== undefined) {
    refs.push({
      sourceId: `CHUNK-${String(chunkNo).padStart(3, '0')}`,
      chunk: chunkNo,
      title: textField(item, ['sourceTitle', 'location']) || sourceTitle(chunk),
    });
  }
  const seen = new Set<string>();
  return refs.filter((ref) => {
    const key = sourceRefKey(ref);
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function matchOriginalSource(
  ref: Record<string, unknown>,
  originalSources: Array<Record<string, unknown>>,
): Record<string, unknown> | undefined {
  const sourceId = textField(ref, ['sourceId', 'blockId']);
  if (sourceId) {
    const byId = originalSources.find((source) => textField(source, ['sourceId', 'blockId']) === sourceId);
    if (byId) return byId;
  }

  const chapterIndex = numericField(ref, ['chapterIndex']);
  if (chapterIndex !== undefined) {
    const byChapter = originalSources.find(
      (source) => numericField(source, ['chapterIndex']) === chapterIndex,
    );
    if (byChapter) return byChapter;
  }

  const chunkNo = numericField(ref, ['chunk', 'sourceChunk']);
  if (chunkNo !== undefined) {
    const byChunk = originalSources.find((source) => numericField(source, ['chunk', 'sourceChunk']) === chunkNo);
    if (byChunk) return byChunk;
  }

  const hint = textField(ref, ['sectionPath', 'title', 'sourceTitle', 'location']).toLowerCase();
  if (hint) {
    return originalSources.find((source) => {
      const title = textField(source, ['sectionPath', 'chapterTitle', 'title']).toLowerCase();
      return title && (hint.includes(title) || title.includes(hint));
    });
  }
  return undefined;
}

export function sourceCandidatesForItem(
  item: Record<string, unknown> | null,
  chunk: Record<string, unknown> | undefined,
  originalSources: Array<Record<string, unknown>>,
): Array<Record<string, unknown>> {
  const candidates: Array<Record<string, unknown>> = [];
  for (const ref of sourceRefsForItem(item, chunk)) {
    const source = matchOriginalSource(ref, originalSources);
    if (source) {
      candidates.push({
        ...source,
        evidenceSourceId: textField(ref, ['sourceId', 'blockId']),
        startNodeId: textField(ref, ['startNodeId', 'start_node_id']),
        endNodeId: textField(ref, ['endNodeId', 'end_node_id']),
        reason: textField(ref, ['reason']),
        score: scoreField(ref, ['score']),
      });
    }
  }

  if (candidates.length === 0 && originalSources.length > 0) {
    const hint = textField(item, ['sourceTitle', 'location', 'evidence', 'originalText']).toLowerCase();
    if (hint) {
      candidates.push(...originalSources.filter((source) => {
        const title = textField(source, ['sectionPath', 'chapterTitle', 'title']).toLowerCase();
        return title && (hint.includes(title) || title.includes(hint));
      }).slice(0, 5));
    }
  }

  if (candidates.length === 0 && originalSources.length === 0) {
    const fallback = sourcePayload(chunk);
    if (fallback) candidates.push(fallback);
  }

  const seen = new Set<string>();
  return candidates.filter((source) => {
    const key = sourceCandidateKey(source);
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function findIssueChunk(
  issue: Record<string, unknown> | null,
  chunks: Array<Record<string, unknown>>,
): Record<string, unknown> | undefined {
  if (!chunks.length) return undefined;
  if (!issue) return chunks[0];

  const chunkNo = numericField(issue, ['sourceChunk', 'chunk', 'chunkNo', 'chunkIndex']);
  if (chunkNo !== undefined) {
    const byNo = chunks.find((chunk) => Number(chunk.chunk) === chunkNo);
    if (byNo) return byNo;
  }

  const hint = textField(issue, ['sourceTitle', 'chapterTitle', 'location', 'originalText']);
  const normalizedHint = hint.toLowerCase();
  if (normalizedHint) {
    const byTitle = chunks.find((chunk) => {
      const title = sourceTitle(chunk).toLowerCase();
      return title && (normalizedHint.includes(title) || title.includes(normalizedHint));
    });
    if (byTitle) return byTitle;
  }

  return chunks[0];
}

/**
 * Identity of one source edit. Must stay byte-identical to the backend's
 * SourceEditStore.editKey — a paragraph is keyed by node alone, a table cell by node
 * plus its 1-based logical coordinates. If the two drift, saving a cell edit would
 * fail to replace the previous one and the export would apply a stale correction.
 */
export function sourceEditKey(
  nodeId: string,
  cellRow?: number | null,
  cellColumn?: number | null,
): string {
  if (cellRow === undefined || cellRow === null || cellColumn === undefined || cellColumn === null) {
    return `${nodeId}|-|-`;
  }
  return `${nodeId}|${cellRow}|${cellColumn}`;
}

/** nodeId(+cell) → 已保存的修改文本，供原文面板渲染「已修改」态。 */
export function buildSourceEditMap(edits: Array<Record<string, unknown>>): Map<string, string> {
  const map = new Map<string, string>();
  for (const edit of edits) {
    const nodeId = textField(edit, ['nodeId']);
    if (!nodeId) continue;
    map.set(
      sourceEditKey(nodeId, numericField(edit, ['cellRow']), numericField(edit, ['cellColumn'])),
      String(edit.newText ?? ''),
    );
  }
  return map;
}

export function checkStatusColor(status: string): string {
  if (status === 'Pass') return 'green';
  if (status === 'Partial') return 'orange';
  if (status === 'Fail') return 'red';
  if (status === 'Review') return 'purple';
  return 'default';
}

export function isProblemCheck(item: Record<string, unknown>): boolean {
  const status = textField(item, ['manualStatus', 'status']);
  return status !== 'Pass' && status !== 'N/A';
}

/**
 * Business checks form a complete matrix, so Pass/Fail/Review are all visible.
 * Basic text-quality checks are finding-oriented and only actual failures should
 * occupy the list. This also cleans up legacy task results created before the
 * backend applied the same projection.
 */
export function isVisibleCheckResult(item: Record<string, unknown>): boolean {
  const ruleCode = textField(item, ['rule_code', 'ruleCode']).trim().toUpperCase();
  const checkCode = textField(item, ['check_code', 'checkCode']).trim().toUpperCase();
  const textQuality = ruleCode === 'R-Q'
    || ruleCode === 'R-BASIC-QUALITY'
    || checkCode.startsWith('R-Q-')
    || checkCode.startsWith('R-BASIC-QUALITY-');
  if (!textQuality) return true;
  return textField(item, ['manualStatus', 'status']) === 'Fail';
}

export function isHighConfidenceNotApplicable(item: Record<string, unknown>): boolean {
  const status = textField(item, ['status']).trim().toUpperCase();
  const confidence = textField(item, ['confidence']).trim().toLowerCase();
  return status === 'N/A' && confidence === 'high';
}

export function confidenceLabel(confidence: string): string {
  if (confidence === 'high') return '高置信度';
  if (confidence === 'medium') return '中置信度';
  if (confidence === 'low') return '低置信度';
  if (confidence === 'single') return '单次判定';
  return '需人工校验';
}

export function sourceReasonLabel(reason: string): string {
  if (reason === 'reranker') return '重排命中';
  if (reason === 'vector') return '向量召回';
  if (reason === 'matched_chunk') return '切片匹配';
  if (reason === 'referenced_chapter') return '引用章节';
  return reason || '来源匹配';
}

export function sourceChapterLabel(source: Record<string, unknown> | null | undefined): string {
  const title = textField(source, ['sectionPath', 'chapterTitle', 'title']);
  const titleMatch = title.match(/^\s*(?:第\s*)?([0-9]+(?:\.[0-9]+)*|[一二三四五六七八九十百千万零〇]+)\s*(?:章|节|条|款|部分|[、.．\s]|$)/);
  if (titleMatch) return `章节 ${titleMatch[1]}`;
  const chapterIndex = numericField(source, ['chapterIndex']);
  if (chapterIndex !== undefined) return `章节 ${chapterIndex}`;
  const chunk = numericField(source, ['chunk', 'sourceChunk']);
  if (chunk !== undefined) return `切片 ${chunk}`;
  return '';
}

export const ALLOWED_SOURCE_TAGS = new Set([
  'DIV', 'P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6',
  'TABLE', 'THEAD', 'TBODY', 'TR', 'TH', 'TD',
  'BR', 'STRONG', 'EM', 'UL', 'OL', 'LI',
]);

export function locatorCandidates(locator: string): string[] {
  const normalized = locator.trim();
  if (!normalized) return [];
  return [
    normalized,
    ...normalized
      .split(/\r?\n|[。；;]/)
      .map((part) => part.replace(/^[“"'‘’]+|[”"'‘’]+$/g, '').trim())
      .filter((part) => part.length >= 8),
  ].sort((left, right) => right.length - left.length);
}

export function normalizeLocatorText(value: string): string {
  return value
    .replace(/\|?\s*:?-{3,}:?\s*(?=\||$)/g, ' ')
    .replace(/[|`*_#>]/g, ' ')
    .replace(/\s+/g, '')
    .trim();
}

/** 反向匹配（节点整体落在证据里）所需的最短节点长度，与后端 DocumentEvidenceLocator 保持一致。 */
const MIN_REVERSE_MATCH_LENGTH = 14;
/** 正向匹配所需的最短证据长度，与后端 MIN_CANDIDATE_LENGTH 保持一致。 */
const MIN_FORWARD_MATCH_LENGTH = 6;

/**
 * 后端没给出 startNodeId 时的兜底定位：在渲染出来的节点里找**最可信**的一个。
 *
 * 早期实现是 findIndex + 「任一候选命中即可」，且 compact 正向匹配只要 4 个字符就算数。
 * 结果是靠前节点里一个 4 字的弱匹配，会盖过靠后节点里整句的强匹配，高亮落到无关段落。
 * 这里改为对所有 (节点, 候选) 组合打分取最优：正向匹配按长度加倍计权，反向匹配要求节点
 * 本身足够长，避免标题这类短文本因被证据整体包含而抢先命中。
 */
function findBestLocatorNodeIndex(nodes: HTMLElement[], locator: string): number {
  const candidates = locatorCandidates(locator);
  if (candidates.length === 0) return -1;

  let bestIndex = -1;
  let bestScore = 0;

  nodes.forEach((node, index) => {
    const nodeText = (node.textContent || '').replace(/\s+/g, ' ').trim();
    if (!nodeText) return;
    const compactNodeText = normalizeLocatorText(nodeText);

    for (const candidate of candidates) {
      const value = candidate.replace(/\s+/g, ' ').trim();
      const compactValue = normalizeLocatorText(value);
      let score = 0;

      if (compactValue.length >= MIN_FORWARD_MATCH_LENGTH && compactNodeText.includes(compactValue)) {
        score = compactValue.length * 2;
      } else if (compactNodeText.length >= MIN_REVERSE_MATCH_LENGTH && compactValue.includes(compactNodeText)) {
        score = compactNodeText.length;
      }

      if (score > bestScore) {
        bestScore = score;
        bestIndex = index;
      }
    }
  });

  return bestIndex;
}

export function buildHighlightedSourceHtml(
  html: string,
  startNodeId: string,
  endNodeId: string,
  locator: string,
): string {
  if (!html || typeof DOMParser === 'undefined') return '';
  const documentNode = new DOMParser().parseFromString(`<div id="source-root">${html}</div>`, 'text/html');
  const root = documentNode.getElementById('source-root');
  if (!root) return '';

  for (const element of Array.from(root.querySelectorAll('*'))) {
    if (!ALLOWED_SOURCE_TAGS.has(element.tagName)) {
      element.replaceWith(documentNode.createTextNode(element.textContent || ''));
      continue;
    }
    for (const attribute of Array.from(element.attributes)) {
      const allowed = ['id', 'class', 'data-node-id', 'data-node-type', 'rowspan', 'colspan', 'border'];
      if (!allowed.includes(attribute.name)) {
        element.removeAttribute(attribute.name);
      }
    }
  }

  const nodes = Array.from(root.querySelectorAll<HTMLElement>('[data-node-id]'));
  let startIndex = startNodeId
    ? nodes.findIndex((node) => node.dataset.nodeId === startNodeId)
    : -1;
  let endIndex = endNodeId
    ? nodes.findIndex((node) => node.dataset.nodeId === endNodeId)
    : startIndex;

  if (startIndex < 0) {
    startIndex = findBestLocatorNodeIndex(nodes, locator);
    endIndex = startIndex;
  }

  if (startIndex >= 0) {
    if (endIndex < startIndex) endIndex = startIndex;
    for (let index = startIndex; index <= Math.min(endIndex, nodes.length - 1); index += 1) {
      nodes[index].classList.add('source-node-highlight');
    }
    nodes[startIndex].classList.add('source-node-highlight-start');
  }
  return root.innerHTML;
}
