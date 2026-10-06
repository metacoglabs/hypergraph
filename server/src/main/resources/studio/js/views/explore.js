// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import { api } from '../api.js';
import { button, fill, h, icon, toast } from '../dom.js';
import { ago, count, plural } from '../format.js';
import { GraphView } from '../graph/view.js';
import { Inspector, swatch } from '../inspector.js';

const MODES = [['both', 'Both'], ['hubs', 'Hubs'], ['hulls', 'Hulls']];

export class ExplorePage {
  constructor(studio) {
    this.studio = studio;
    this.title = 'Explore';
    this.generation = null;
    this.generations = [];
    const host = h('div', { class: 'graph-host' });
    this.element = h('section', { class: 'page explore' }, host);
    this.view = new GraphView(host, {
      onSelect: atom => {
        this.inspector.show(atom);
        this.element.classList.toggle('inspecting', Boolean(atom));
      },
      onExpand: atom => this.expand(atom),
      onChange: () => this.refresh()
    });
    this.inspector = new Inspector(this.element, this.view, {
      onExpand: atom => this.expand(atom),
      onQuery: script => studio.navigate('console', { script, run: true })
    });
    this.legend = h('div', { class: 'overlay overlay-card legend' });
    this.status = h('div', { class: 'overlay overlay-card view-status' });
    this.search = h('input', { class: 'input', type: 'search', placeholder: 'Find: atom id, Type:key or a type name', 'aria-label': 'Find atoms' });
    this.search.addEventListener('keydown', event => event.key === 'Enter' && this.find(this.search.value));
    this.slider = h('input', { type: 'range', min: 0, max: 0, step: 1, 'aria-label': 'Generation' });
    this.sliderLabel = h('span', { class: 'label' });
    this.slider.addEventListener('input', () => this.scrub());
    this.timeline = h('div', { class: 'overlay overlay-card timeline' },
      icon('clock'), this.slider, this.sliderLabel,
      button('Live', { icon: 'live', variant: 'small', title: 'Return to the latest generation', onClick: () => this.goLive() }));
    this.empty = h('div', { class: 'explore-empty' }, h('div', {},
      h('div', { class: 'boot-mark' }),
      h('h2', {}, 'Explore the hypergraph'),
      h('p', {}, 'Load a sample, search for an atom, or open results from the console. Hyperedges appear as hubs with role-labelled spokes and translucent hulls around their members.'),
      button('Load a sample', { icon: 'explore', variant: 'primary', onClick: () => this.sample() })));
    this.element.append(
      this.legend,
      h('div', { class: 'overlay overlay-card explore-search' },
        icon('search'), this.search,
        button('Sample', { variant: 'small', title: 'Replace the view with a sample of every hyperedge type', onClick: () => this.sample() })),
      this.status,
      this.timeline,
      h('div', { class: 'overlay overlay-card graph-controls' },
        button('', { icon: 'plus', title: 'Zoom in (+)', onClick: () => this.view.zoomBy(1.3) }),
        button('', { icon: 'minus', title: 'Zoom out (−)', onClick: () => this.view.zoomBy(1 / 1.3) }),
        button('', { icon: 'fit', title: 'Fit to view (F)', onClick: () => this.view.fit() }),
        button('', { icon: 'reheat', title: 'Re-run the layout', onClick: () => this.view.reheat() }),
        h('div', { class: 'sep' }),
        button('', { icon: 'image', title: 'Export PNG', onClick: () => this.view.exportPng() }),
        button('', { icon: 'trash', title: 'Clear the view', onClick: () => this.view.clear() })),
      this.empty);
    this.refresh();
  }

  async activate(params = {}) {
    await this.loadHistory();
    if (params.ids?.length) {
      await this.load({ ids: params.ids, connect: true, limit: 800 }, { replace: true });
    } else if (params.type) {
      await this.load({ type: params.type, limit: 400 }, { replace: true });
    } else if (this.view.empty) {
      await this.sample();
    }
  }

  deactivate() {}

  async load(parameters, options = {}) {
    try {
      const payload = await api.graph({ ...parameters, generation: this.generation ?? undefined });
      if (!payload.atoms.length && !options.replace) {
        toast('Nothing matched');
        return null;
      }
      this.view.load(payload, options);
      if (!payload.complete) {
        toast('The neighbourhood was truncated at the atom limit; expand atoms individually to see more.');
      }
      return payload;
    } catch (error) {
      this.studio.notify(error);
      return null;
    }
  }

  sample() {
    return this.load({ limit: 400 }, { replace: true });
  }

  expand(atom) {
    return this.load({ ids: [atom.id], expand: 1, limit: 250 }, { anchor: atom.id });
  }

  async find(text) {
    const query = text.trim();
    const types = this.studio.schemaTypes();
    let parameters;
    let match;
    if ((match = /^@?(\d+)$/.exec(query))) {
      parameters = { ids: [match[1]] };
    } else if ((match = /^@?([A-Za-z_]\w*):'?(.*?)'?$/.exec(query))) {
      parameters = { type: match[1], key: match[2] };
    } else if (types.some(type => type.name === query)) {
      parameters = { type: query, limit: 300 };
    } else {
      toast('Search accepts an atom id (42), a keyed reference (Person:alice) or a type name', 'error');
      return;
    }
    const before = new Set(this.view.atoms.keys());
    const payload = await this.load({ expand: parameters.key || parameters.ids ? 1 : 0, ...parameters });
    const target = payload?.atoms.find(atom => parameters.ids ? String(atom.id) === parameters.ids[0] : parameters.key ? atom.key === parameters.key : !before.has(atom.id));
    if (target) {
      setTimeout(() => this.view.select(target.id, { center: true }), 60);
    }
  }

  async loadHistory() {
    try {
      const history = await api.history();
      this.generations = history.generations;
      this.slider.max = Math.max(0, this.generations.length - 1);
      if (this.generation === null) {
        this.slider.value = this.slider.max;
      }
      this.refreshTimeline();
    } catch (error) {
      this.studio.notify(error);
    }
  }

  scrub() {
    clearTimeout(this.scrubTimer);
    const entry = this.generations[Number(this.slider.value)];
    const latest = Number(this.slider.value) === this.generations.length - 1;
    this.generation = latest ? null : entry.id;
    this.refreshTimeline();
    this.scrubTimer = setTimeout(() => this.reload(), 220);
  }

  goLive() {
    this.generation = null;
    this.loadHistory().then(() => this.reload());
  }

  reload() {
    const ids = [...this.view.atoms.keys()];
    if (!ids.length) {
      return this.sample();
    }
    return this.load({ ids, limit: ids.length + 200 }, { replace: true });
  }

  refreshTimeline() {
    const entry = this.generations[Number(this.slider.value)];
    const historical = this.generation !== null;
    this.timeline.classList.toggle('historical', historical);
    this.sliderLabel.textContent = !entry ? 'No retained history'
      : historical ? `Generation ${entry.id} · ${ago(entry.wallTime)}` : `Live · generation ${entry.id}`;
  }

  refresh() {
    const { nodes, edges } = this.view.counts();
    this.empty.style.display = this.view.empty ? '' : 'none';
    this.status.style.display = this.view.empty ? 'none' : '';
    this.status.textContent = `${plural(nodes, 'node')} · ${plural(edges, 'hyperedge')}${this.generation !== null ? ` · as of generation ${this.generation}` : ''}`;
    fill(this.legend,
      h('div', { class: 'legend-head' }, h('span', {}, 'In view'), h('span', {}, count(this.view.atoms.size))),
      h('div', { class: 'legend-list' }, this.view.summary().map(entry => h('button', {
        class: `legend-item${entry.hidden ? ' hidden' : ''}`, type: 'button', title: entry.hidden ? 'Show this type' : 'Hide this type',
        onClick: () => this.view.toggleType(entry.typeId)
      }, swatch(entry), h('span', {}, entry.type), h('span', { class: 'count' }, count(entry.count))))),
      h('div', { class: 'legend-head' }, h('span', {}, 'Hyperedges')),
      h('div', { class: 'segmented', role: 'group' }, MODES.map(([mode, label]) => h('button', {
        type: 'button', class: this.view.mode === mode ? 'active' : '', onClick: () => { this.view.setMode(mode); this.refresh(); }
      }, label))),
      h('div', { class: 'toggle-row' }, h('span', {}, 'Node labels'), h('button', {
        type: 'button', role: 'switch', 'aria-checked': String(this.view.labels), class: `switch${this.view.labels ? ' on' : ''}`,
        onClick: () => { this.view.setLabels(!this.view.labels); this.refresh(); }
      })));
  }
}
