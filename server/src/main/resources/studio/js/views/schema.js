import { button, fill, h } from '../dom.js';
import { count, kind, timestamp } from '../format.js';
import { swatch } from '../inspector.js';

export class SchemaPage {
  constructor(studio) {
    this.studio = studio;
    this.title = 'Schema';
    this.body = h('div', { class: 'page-scroll' });
    this.element = h('section', { class: 'page' }, this.body);
  }

  async activate() {
    const schema = await this.studio.refreshSchema();
    if (schema) {
      this.render(schema);
    }
  }

  deactivate() {}

  render(schema) {
    const nodes = schema.types.filter(type => type.kind === 'NODE');
    const edges = schema.types.filter(type => type.kind !== 'NODE');
    const total = schema.types.reduce((sum, type) => sum + type.count, 0);
    fill(this.body,
      h('div', { class: 'schema-summary' },
        h('span', { class: 'pill' }, h('strong', {}, count(nodes.length)), 'node types'),
        h('span', { class: 'pill' }, h('strong', {}, count(edges.length)), 'hyperedge types'),
        h('span', { class: 'pill' }, h('strong', {}, count(total)), 'atoms visible to you'),
        h('span', { class: 'pill' }, 'generation', h('strong', {}, schema.generation))),
      schema.types.length
        ? [this.section('Hyperedge types', edges), this.section('Node types', nodes)]
        : h('div', { class: 'card empty' }, 'No types are defined yet. Create one in the console, for example ', h('code', {}, 'CREATE NODE TYPE Person (name STRING INDEXED);')),
      h('h2', { class: 'section-title' }, 'Branches'),
      h('div', { class: 'card' }, h('div', { class: 'table-wrap' }, h('table', { class: 'grid' },
        h('thead', {}, h('tr', {}, ['Id', 'Name', 'State', 'Parent', 'Base generation', 'Created'].map(label => h('th', {}, label)))),
        h('tbody', {}, schema.branches.map(branch => h('tr', {},
          h('td', {}, branch.id), h('td', {}, branch.name),
          h('td', {}, h('span', { class: `badge ${branch.state === 'ACTIVE' ? 'ok' : ''}` }, branch.state)),
          h('td', {}, branch.id === 0 ? '—' : branch.parent), h('td', {}, branch.baseGeneration),
          h('td', {}, branch.createdAt ? timestamp(branch.createdAt) : '—'))))))));
  }

  section(title, types) {
    if (!types.length) {
      return null;
    }
    return [h('h2', { class: 'section-title' }, title), h('div', { class: 'schema-grid' }, types.map(type => this.card(type)))];
  }

  card(type) {
    const edge = type.kind !== 'NODE';
    const keyword = edge ? 'EDGE' : 'NODE';
    return h('article', { class: 'card type-card' },
      h('header', { class: 'card-head' },
        swatch({ typeId: type.id, kind: type.kind }), h('h3', {}, type.name), h('span', { class: `badge${edge ? ' accent' : ''}` }, kind(type.kind)),
        h('span', { class: 'count' }, count(type.count))),
      type.properties.length
        ? h('div', { class: 'props' }, type.properties.map(property => h('div', { class: 'prop' },
          h('span', { class: 'name' }, property.name),
          property.indexed ? h('span', { class: 'badge accent' }, 'indexed') : null,
          property.required ? h('span', { class: 'badge warn' }, 'required') : null,
          h('span', { class: 'type' }, property.type))),
          type.jsonIndexes.map(index => h('div', { class: 'prop' },
            h('span', { class: 'name mono' }, index.path), h('span', { class: 'badge accent' }, 'json index'), h('span', { class: 'type' }, index.type))))
        : h('div', { class: 'empty' }, 'No declared properties'),
      edge && type.roles.length ? h('div', { class: 'roles' }, h('span', { class: 'muted' }, 'Roles'), type.roles.map(role => h('span', { class: 'role' }, role))) : null,
      h('footer', { class: 'card-foot' },
        button('Explore', { icon: 'explore', variant: 'small', onClick: () => this.studio.navigate('explore', { type: type.name }) }),
        button('Query', { icon: 'console', variant: 'small', onClick: () => this.studio.navigate('console', { script: `MATCH ${keyword} x:${type.name} RETURN x LIMIT 25;`, run: true }) }),
        h('span', { class: 'topbar-spacer' }),
        h('span', { class: 'muted' }, `v${type.version} · #${type.id}`)));
  }
}
