// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import { api } from '../api.js';
import { button, clear, h, icon, popover, storage } from '../dom.js';
import { HqlEditor } from '../editor.js';
import { count, micros, plural } from '../format.js';
import { GraphView } from '../graph/view.js';
import { displayValue, Inspector } from '../inspector.js';

const HISTORY_LIMIT = 100;
const STREAM_LIMIT = 40;

function prettyJson(value) {
  const text = JSON.stringify(value, null, 2);
  const pre = h('pre', { class: 'json-view mono' });
  const pattern = /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g;
  let last = 0;
  for (const match of text.matchAll(pattern)) {
    pre.append(text.slice(last, match.index));
    const [whole, string, colon, literal, number] = match;
    if (string) {
      pre.append(h('span', { class: colon ? 'json-key' : 'json-string' }, string), colon ?? '');
    } else if (literal) {
      pre.append(h('span', { class: 'json-literal' }, literal));
    } else if (number) {
      pre.append(h('span', { class: 'json-number' }, number));
    } else {
      pre.append(whole);
    }
    last = match.index + whole.length;
  }
  pre.append(text.slice(last));
  return pre;
}

class Frame {
  constructor(page, script, result, position) {
    this.page = page;
    this.script = script;
    this.result = result;
    this.graph = null;
    this.content = h('div', { class: 'frame-content' });
    const failed = Boolean(result.error);
    const tabular = (result.columns ?? []).length > 0;
    const tabs = failed ? [] : [
      result.atoms?.length ? ['graph', 'Graph', 'graph'] : null,
      tabular ? ['table', 'Table', 'table'] : null,
      tabular || result.trace ? ['json', 'JSON', 'braces'] : null,
      result.trace ? ['plan', 'Plan', 'plan'] : null
    ].filter(Boolean);
    this.tabButtons = new Map(tabs.map(([id, label, iconName]) => [id, h('button', {
      class: 'frame-tab', type: 'button', title: label, onClick: () => this.show(id)
    }, icon(iconName), label)]));
    this.element = h('article', { class: `frame${failed ? ' error' : ''}` },
      h('header', { class: 'frame-head' },
        h('span', { class: `badge ${failed ? 'danger' : 'accent'}` }, result.statement),
        h('div', { class: 'frame-script', title: script }, script.replace(/\s+/g, ' ')),
        h('div', { class: 'frame-meta' }, this.meta(position)),
        h('div', { class: 'frame-actions' },
          button('', { icon: 'refresh', variant: 'ghost small', title: 'Run again', onClick: () => page.run(script) }),
          button('', { icon: 'copy', variant: 'ghost small', title: 'Edit in editor', onClick: () => page.edit(script) }),
          button('', { icon: 'collapse', variant: 'ghost small', title: 'Collapse', onClick: () => this.element.classList.toggle('collapsed') }),
          button('', { icon: 'close', variant: 'ghost small', title: 'Close', onClick: () => this.close() }))),
      failed
        ? h('div', { class: 'frame-error' }, h('span', { class: 'badge danger code' }, result.error.code), '\n', result.error.message,
          result.error.retryable ? '\nThe transaction conflicted with a concurrent commit and can be retried.' : '')
        : tabs.length
          ? h('div', { class: 'frame-body' }, h('nav', { class: 'frame-tabs' }, [...this.tabButtons.values()]), this.content)
          : h('div', { class: 'frame-message compact' }, result.message));
    if (tabs.length) {
      this.show(tabs[0][0]);
    }
  }

  meta(position) {
    const parts = [];
    if (position.total > 1) {
      parts.push(`${position.index + 1}/${position.total}`);
    }
    if (this.result.rows?.length || this.result.columns?.length) {
      parts.push(plural(this.result.rows.length, 'row'));
    }
    if (this.result.elapsedMicros !== undefined) {
      parts.push(micros(this.result.elapsedMicros));
    }
    if (this.result.trace) {
      parts.push(`gen ${this.result.trace.generation}`);
    }
    return parts.join(' · ');
  }

  show(tab) {
    for (const [id, element] of this.tabButtons) {
      element.classList.toggle('active', id === tab);
    }
    if (this.graph && tab !== 'graph') {
      this.graph.view.destroy();
      this.graph = null;
    }
    clear(this.content);
    const renderers = { graph: () => this.renderGraph(), table: () => this.renderTable(), json: () => prettyJson(this.result), plan: () => this.renderPlan() };
    this.content.append(renderers[tab]());
  }

  renderTable() {
    const { columns, rows, message } = this.result;
    const cell = value => {
      if (value && typeof value === 'object' && !Array.isArray(value) && 'id' in value && 'label' in value) {
        return h('button', { class: 'atom-chip', type: 'button', title: 'Open in Explore', onClick: () => this.page.studio.navigate('explore', { ids: [value.id] }) },
          h('span', { class: 'ref' }, `@${value.id}`), value.label || null);
      }
      if (Array.isArray(value)) {
        return value.map(item => [cell(item), ' ']);
      }
      return displayValue(value);
    };
    return h('div', {},
      h('div', { class: 'table-wrap' }, h('table', { class: 'grid' },
        h('thead', {}, h('tr', {}, h('th', { class: 'row-index' }, '#'), columns.map(column => h('th', {}, column)))),
        h('tbody', {}, rows.map((row, index) => h('tr', {},
          h('td', { class: 'row-index' }, index + 1),
          row.map(value => h('td', { class: typeof value === 'number' ? 'number' : '' }, cell(value)))))))),
      h('div', { class: 'frame-message compact muted' }, message));
  }

  renderPlan() {
    const trace = this.result.trace;
    return h('div', { class: 'plan' },
      h('pre', { class: 'mono' }, trace.plan),
      h('dl', {},
        h('dt', {}, 'Generation'), h('dd', {}, trace.generation),
        h('dt', {}, 'Estimated rows'), h('dd', {}, count(trace.estimatedRows)),
        h('dt', {}, 'Output rows'), h('dd', {}, count(this.result.rows?.length ?? 0)),
        h('dt', {}, 'Pages read'), h('dd', {}, count(trace.pagesRead)),
        h('dt', {}, 'Cache hits'), h('dd', {}, count(trace.cacheHits)),
        h('dt', {}, 'Elapsed'), h('dd', {}, micros(trace.elapsedMicros))));
  }

  renderGraph() {
    const host = h('div', { class: 'graph-host' });
    const container = h('div', { class: 'frame-graph' }, host);
    const view = new GraphView(host, {
      onSelect: atom => inspector.show(atom),
      onExpand: atom => this.expand(atom)
    });
    const inspector = new Inspector(container, view, {
      onExpand: atom => this.expand(atom),
      onQuery: script => this.page.edit(script)
    });
    container.append(h('div', { class: 'overlay overlay-card graph-controls' },
      button('', { icon: 'plus', title: 'Zoom in', onClick: () => view.zoomBy(1.3) }),
      button('', { icon: 'minus', title: 'Zoom out', onClick: () => view.zoomBy(1 / 1.3) }),
      button('', { icon: 'fit', title: 'Fit to view', onClick: () => view.fit() }),
      h('div', { class: 'sep' }),
      button('', { icon: 'explore', title: 'Open in Explore', onClick: () => this.page.studio.navigate('explore', { ids: [...view.atoms.keys()] }) })));
    this.graph = { view, inspector };
    api.graph({ ids: this.result.atoms, connect: true, limit: 600 })
      .then(payload => view.load(payload))
      .catch(error => container.append(h('div', { class: 'frame-error overlay' }, error.message)));
    return container;
  }

  async expand(atom) {
    try {
      const payload = await api.graph({ ids: [atom.id], expand: 1, limit: 200 });
      this.graph?.view.load(payload, { anchor: atom.id });
    } catch (error) {
      this.page.studio.notify(error);
    }
  }

  close() {
    this.graph?.view.destroy();
    this.element.remove();
  }
}

export class ConsolePage {
  constructor(studio) {
    this.studio = studio;
    this.title = 'Console';
    this.history = storage.get('history', []);
    this.cursor = this.history.length;
    this.editor = new HqlEditor({
      placeholder: 'SHOW TYPES;   MATCH EDGE e:Type WHERE e CONTAINS (@1, @2) RETURN e;',
      onRun: () => this.run(),
      onHistory: step => this.recall(step),
      completions: () => studio.completions()
    });
    this.runButton = button('Run', { icon: 'play', variant: 'primary', kbd: navigator.platform.includes('Mac') ? '⌘↵' : 'Ctrl↵', onClick: () => this.run() });
    const historyButton = button('History', { icon: 'history', variant: 'ghost', onClick: () => this.showHistory(historyButton) });
    const examplesButton = button('Examples', { icon: 'book', variant: 'ghost', onClick: () => this.showExamples(examplesButton) });
    this.stream = h('div', { class: 'stream', 'aria-live': 'polite' });
    this.element = h('section', { class: 'page console' },
      h('div', { class: 'console-editor' },
        this.editor.element,
        h('div', { class: 'console-toolbar' },
          this.runButton,
          button('Explain', { icon: 'plan', onClick: () => this.run(`EXPLAIN ${this.editor.value.trim()}`) }),
          historyButton,
          examplesButton,
          button('', { icon: 'trash', variant: 'ghost', title: 'Clear results', onClick: () => this.clearStream() }),
          h('span', { class: 'hint' }, 'Ctrl+Space completes · Ctrl+↑/↓ recalls history'))),
      this.stream);
    this.renderWelcome();
  }

  activate(params = {}) {
    this.editor.setTypes(this.studio.schemaTypes().map(type => type.name));
    if (params.script) {
      this.editor.value = params.script;
      if (params.run) {
        this.run(params.script);
      }
    }
    requestAnimationFrame(() => this.editor.focus());
  }

  deactivate() {}

  renderWelcome() {
    this.stream.append(h('article', { class: 'frame' },
      h('div', { class: 'frame-message' },
        h('strong', {}, 'HQL console. '),
        'Write one or more statements separated by semicolons and press ', h('kbd', {}, 'Ctrl/⌘ + Enter'), '. ',
        'Results that return atoms open as an interactive hypergraph; double-click an atom to expand its hyperedges.')));
  }

  edit(script) {
    this.editor.value = script;
    this.editor.focus();
  }

  recall(step) {
    if (!this.history.length) {
      return null;
    }
    this.cursor = Math.min(this.history.length, Math.max(0, this.cursor + step));
    return this.cursor === this.history.length ? '' : this.history[this.cursor];
  }

  remember(script) {
    if (this.history.at(-1) !== script) {
      this.history.push(script);
      this.history = this.history.slice(-HISTORY_LIMIT);
      storage.set('history', this.history);
    }
    this.cursor = this.history.length;
  }

  async run(script = this.editor.value) {
    const text = script.trim();
    if (!text || this.running) {
      return;
    }
    this.running = true;
    this.runButton.disabled = true;
    this.remember(text);
    try {
      const response = await api.query(text);
      this.studio.observe(response);
      const frames = response.results.map((result, index) => new Frame(this, result.text || text, result, { index, total: response.results.length }));
      for (const frame of frames.reverse()) {
        this.stream.prepend(frame.element);
      }
      while (this.stream.children.length > STREAM_LIMIT) {
        this.stream.lastElementChild.remove();
      }
      this.stream.scrollTop = 0;
      if (response.results.some(result => !result.error && !result.columns?.length)) {
        this.studio.refreshSchema();
      }
    } catch (error) {
      this.studio.notify(error);
    } finally {
      this.running = false;
      this.runButton.disabled = false;
    }
  }

  clearStream() {
    [...this.stream.children].forEach(child => child.remove());
  }

  showHistory(anchor) {
    const entries = [...this.history].reverse().slice(0, 40);
    const close = popover(anchor, entries.length
      ? entries.map(entry => h('button', { class: 'popover-item', type: 'button', onClick: () => { this.edit(entry); close(); } }, h('code', {}, entry.replace(/\s+/g, ' '))))
      : h('div', { class: 'popover-empty' }, 'No statements have been run yet.'));
  }

  showExamples(anchor) {
    const types = this.studio.schemaTypes();
    const node = types.find(type => type.kind === 'NODE');
    const edge = types.find(type => type.kind !== 'NODE');
    const examples = [
      ['Show the schema', 'SHOW TYPES;'],
      node ? [`Sample ${node.name} nodes`, `MATCH NODE n:${node.name} RETURN n LIMIT 25;`] : null,
      edge ? [`Sample ${edge.name} hyperedges`, `MATCH EDGE e:${edge.name} RETURN e, card(e) LIMIT 25;`] : null,
      edge ? ['Hyperedges containing two atoms', `MATCH EDGE e:${edge.name} WHERE e CONTAINS (@1, @2) RETURN e;`] : null,
      ['Members of a hyperedge', 'MEMBERS OF @1;'],
      ['Hyperedges incident to an atom', 'INCIDENT TO @1;'],
      ['Co-membership neighbours', 'NEIGHBORS OF @1 THRESHOLD 1;'],
      ['Query plan', node ? `EXPLAIN MATCH NODE n:${node.name} RETURN count(*);` : 'EXPLAIN SHOW TYPES;'],
      ['Time travel', 'AT GENERATION 1 SHOW TYPES;'],
      ['Commit history', 'HISTORY LIMIT 20;'],
      ['Branches', 'SHOW BRANCHES;'],
      ['Engine statistics', 'STATS;'],
      ['Current principal', 'WHOAMI;']
    ].filter(Boolean);
    const close = popover(anchor, examples.map(([title, script]) => h('button', {
      class: 'popover-item', type: 'button', onClick: () => { this.edit(script); close(); }
    }, h('div', { class: 'title' }, title), h('code', {}, script))));
  }
}
