import { h } from './dom.js';

export const KEYWORDS = [
  'ABORT', 'ACTIVITY', 'ADD', 'ALTER', 'AND', 'ANY', 'AS', 'ASC', 'AT', 'ATTRIBUTES', 'AUTHENTICATE', 'BEGIN', 'BETWEEN', 'BRANCH',
  'BRANCHES', 'BUCKET', 'BY', 'CARD', 'CARDINALITY', 'CHECKPOINT', 'CLOCK', 'CLOSURE', 'COMMIT', 'COMPACT', 'CONFIDENCE', 'CONFLICT',
  'CONTAINS', 'CONTINUOUS', 'CREATE', 'DECIMAL', 'DEGREE', 'DELETE', 'DEPTH', 'DESC', 'DESCRIBE', 'DIFF', 'DIM', 'DOCUMENT', 'DROP',
  'DURING', 'EDGE', 'EDGES', 'EMBED', 'EVIDENCE', 'EXPAND', 'EXPLAIN', 'FALSE', 'FLOAT', 'FOR', 'FORMAT', 'FRESH', 'FROM', 'GATHER',
  'GENERATION', 'HAS', 'HISTORY', 'IN', 'INCIDENT', 'INDEX', 'INDEXED', 'INSERT', 'INT', 'INTO', 'JOIN', 'JSON', 'KEY', 'LIMIT',
  'MAIN', 'MATCH', 'MEMBERS', 'MERGE', 'MODEL', 'NEIGHBORS', 'NODE', 'NODES', 'NOT', 'NULL', 'OBSERVED', 'OF', 'ON', 'OR', 'ORDER',
  'ORDERED', 'OVER', 'OVERLAP', 'PASSWORD', 'PATTERN', 'POLICY', 'PROPAGATE', 'QUALIFY', 'QUOTA', 'REDUCE', 'REFRESH', 'REMOVE',
  'REQUIRED', 'RESOLUTION', 'RETURN', 'ROLE', 'ROLES', 'ROLLBACK', 'SAMPLE', 'SCATTER', 'SCHEMA', 'SEED', 'SERIALIZABLE', 'SET',
  'SHARED', 'SHOW', 'SIGNAL', 'SIGNALS', 'SIMILAR', 'SNAPSHOT', 'SOURCE', 'STATE', 'STATS', 'STRING', 'SUBSET', 'SUPPORTED', 'SWAPS',
  'TENANT', 'TENANTS', 'TEXT', 'THRESHOLD', 'TIMESTAMP', 'TO', 'TOP', 'TRACE', 'TRUE', 'TYPE', 'TYPES', 'UNSET', 'USE', 'USER',
  'USERS', 'VALID', 'VECTOR', 'VIEW', 'VIEWS', 'WEIGHT', 'WEIGHTED', 'WHERE', 'WHOAMI'
];

const KEYWORD_SET = new Set(KEYWORDS);
const TOKENS = /(--[^\n]*)|('(?:[^']|'')*'?)|(\$[A-Za-z_]\w*)|(@\d+|@[A-Za-z_]\w*(?::'(?:[^']|'')*'?)?)|(\b\d+(?:\.\d+)?\b)|([A-Za-z_]\w*)|([\s\S])/g;
const LINE_HEIGHT = 20;
const PADDING = 10;

const escape = text => text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');

export function highlight(source, types = new Set()) {
  let html = '';
  for (const match of source.matchAll(TOKENS)) {
    const [text, comment, string, variable, reference, number, word] = match;
    const kind = comment ? 'comment' : string ? 'string' : variable ? 'variable' : reference ? 'reference' : number ? 'number'
      : word && KEYWORD_SET.has(word.toUpperCase()) ? 'keyword' : word && types.has(word) ? 'type' : null;
    html += kind ? `<span class="tok-${kind}">${escape(text)}</span>` : escape(text);
  }
  return html;
}

export class HqlEditor {
  constructor({ placeholder = '', onRun = () => {}, onHistory = () => null, completions = () => [] } = {}) {
    this.handlers = { onRun, onHistory, completions };
    this.types = new Set();
    this.textarea = h('textarea', { spellcheck: false, autocapitalize: 'off', autocomplete: 'off', placeholder, 'aria-label': 'HQL statements' });
    this.highlighted = h('pre', { 'aria-hidden': 'true' });
    this.gutter = h('div', { class: 'editor-gutter', 'aria-hidden': 'true' });
    this.code = h('div', { class: 'editor-code' }, this.highlighted, this.textarea);
    this.element = h('div', { class: 'editor' }, this.gutter, this.code);
    this.suggestions = null;
    this.textarea.addEventListener('input', () => {
      this.refresh();
      this.suggest();
    });
    this.textarea.addEventListener('scroll', () => this.syncScroll());
    this.textarea.addEventListener('keydown', event => this.onKey(event));
    this.textarea.addEventListener('blur', () => setTimeout(() => this.closeSuggestions(), 120));
    this.refresh();
  }

  get value() {
    return this.textarea.value;
  }

  set value(text) {
    this.textarea.value = text;
    this.refresh();
  }

  setTypes(names) {
    this.types = new Set(names);
    this.refresh();
  }

  focus() {
    this.textarea.focus();
    const end = this.textarea.value.length;
    this.textarea.setSelectionRange(end, end);
  }

  refresh() {
    this.highlighted.innerHTML = `${highlight(this.textarea.value, this.types)}\n `;
    const lines = Math.max(3, this.textarea.value.split('\n').length);
    this.gutter.textContent = Array.from({ length: lines }, (_, index) => index + 1).join('\n');
    this.textarea.style.height = 'auto';
    this.textarea.style.height = `${Math.max(3 * LINE_HEIGHT + 2 * PADDING, this.textarea.scrollHeight)}px`;
    this.syncScroll();
  }

  syncScroll() {
    this.highlighted.scrollTop = this.textarea.scrollTop;
    this.gutter.scrollTop = this.textarea.scrollTop;
  }

  insert(text) {
    const { selectionStart, selectionEnd, value } = this.textarea;
    this.textarea.value = value.slice(0, selectionStart) + text + value.slice(selectionEnd);
    this.textarea.setSelectionRange(selectionStart + text.length, selectionStart + text.length);
    this.refresh();
  }

  onKey(event) {
    const modifier = event.metaKey || event.ctrlKey;
    if (this.suggestions) {
      const actions = {
        ArrowDown: () => this.moveSuggestion(1),
        ArrowUp: () => this.moveSuggestion(-1),
        Tab: () => this.acceptSuggestion(),
        Enter: () => this.acceptSuggestion(),
        Escape: () => this.closeSuggestions()
      };
      if (actions[event.key] && !modifier) {
        event.preventDefault();
        actions[event.key]();
        return;
      }
    }
    if (event.key === 'Enter' && modifier) {
      event.preventDefault();
      this.closeSuggestions();
      this.handlers.onRun(this.value);
    } else if (event.key === 'Tab' && !event.shiftKey) {
      event.preventDefault();
      this.insert('  ');
    } else if ((event.key === 'ArrowUp' || event.key === 'ArrowDown') && event.ctrlKey) {
      const recalled = this.handlers.onHistory(event.key === 'ArrowUp' ? -1 : 1);
      if (recalled !== null) {
        event.preventDefault();
        this.value = recalled;
        this.focus();
      }
    } else if (event.key === ' ' && event.ctrlKey) {
      event.preventDefault();
      this.suggest(true);
    }
  }

  suggest(forced = false) {
    const caret = this.textarea.selectionStart;
    const before = this.textarea.value.slice(0, caret);
    const word = /[A-Za-z_]\w*$/.exec(before)?.[0] ?? '';
    if ((!forced && word.length < 2) || /'[^']*$/.test(before.split('\n').at(-1).replace(/'[^']*'/g, ''))) {
      this.closeSuggestions();
      return;
    }
    const lower = word.toLowerCase();
    const candidates = [
      ...this.handlers.completions().filter(entry => entry.text.toLowerCase().startsWith(lower)),
      ...KEYWORDS.filter(keyword => keyword.toLowerCase().startsWith(lower)).map(text => ({ text, kind: 'keyword' }))
    ].filter(entry => entry.text !== word).slice(0, 9);
    if (!candidates.length) {
      this.closeSuggestions();
      return;
    }
    const lines = before.split('\n');
    const column = lines.at(-1).length - word.length;
    const width = this.characterWidth();
    const list = h('div', { class: 'suggest', role: 'listbox' });
    this.suggestions = { word, candidates, active: 0, list };
    candidates.forEach((candidate, index) => list.append(h('div', {
      class: `suggest-item${index === 0 ? ' active' : ''}`,
      role: 'option',
      onMousedown: event => {
        event.preventDefault();
        this.suggestions.active = index;
        this.acceptSuggestion();
      }
    }, h('span', {}, candidate.text), h('span', { class: 'kind' }, candidate.kind))));
    this.code.querySelector('.suggest')?.remove();
    list.style.left = `${Math.min(PADDING + column * width, this.code.clientWidth - 220)}px`;
    list.style.top = `${PADDING + lines.length * LINE_HEIGHT - this.textarea.scrollTop + 2}px`;
    this.code.append(list);
  }

  characterWidth() {
    if (!this.width) {
      const context = document.createElement('canvas').getContext('2d');
      context.font = getComputedStyle(this.textarea).font;
      this.width = context.measureText('MMMMMMMMMM').width / 10 || 7.5;
    }
    return this.width;
  }

  moveSuggestion(step) {
    const { candidates, list } = this.suggestions;
    this.suggestions.active = (this.suggestions.active + step + candidates.length) % candidates.length;
    [...list.children].forEach((item, index) => item.classList.toggle('active', index === this.suggestions.active));
    list.children[this.suggestions.active].scrollIntoView({ block: 'nearest' });
  }

  acceptSuggestion() {
    const { word, candidates, active } = this.suggestions;
    const caret = this.textarea.selectionStart;
    this.textarea.setSelectionRange(caret - word.length, caret);
    this.insert(candidates[active].text);
    this.closeSuggestions();
  }

  closeSuggestions() {
    this.suggestions?.list.remove();
    this.suggestions = null;
  }
}
