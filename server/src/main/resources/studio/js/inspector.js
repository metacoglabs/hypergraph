import { button, h, toast } from './dom.js';
import { count, kind } from './format.js';
import { typeColor } from './palette.js';

const isEdge = atom => atom.kind !== 'NODE';

export function swatch(atom) {
  const element = h('span', { class: `swatch${isEdge(atom) ? ' edge' : ''}` });
  element.style.background = typeColor(atom.typeId);
  return element;
}

export function reference(atom) {
  return atom.key ? `@${atom.type}:'${atom.key.replaceAll("'", "''")}'` : `@${atom.id}`;
}

export function displayValue(value) {
  if (value === null || value === undefined) {
    return h('span', { class: 'null' }, 'null');
  }
  if (typeof value === 'object') {
    return h('code', {}, JSON.stringify(value));
  }
  return String(value);
}

export class Inspector {
  constructor(host, view, { onExpand, onQuery }) {
    this.view = view;
    this.actions = { onExpand, onQuery };
    this.element = h('aside', { class: 'inspector overlay overlay-card hidden', 'aria-label': 'Atom inspector' });
    host.append(this.element);
  }

  show(atom) {
    this.element.classList.toggle('hidden', !atom);
    if (!atom) {
      this.element.replaceChildren();
      return;
    }
    this.element.replaceChildren(this.head(atom), h('div', { class: 'inspector-body' }, this.body(atom)));
  }

  head(atom) {
    const pinned = this.view.pinned(atom.id);
    const query = isEdge(atom) ? `MEMBERS OF @${atom.id};` : `INCIDENT TO @${atom.id};`;
    return h('div', { class: 'inspector-head' },
      h('div', { class: 'inspector-type' },
        swatch(atom), h('span', {}, atom.type), h('span', { class: 'badge' }, kind(atom.kind)),
        h('span', { class: 'topbar-spacer' }),
        button('', { icon: 'close', variant: 'ghost small', title: 'Close', onClick: () => this.view.select(null) })),
      h('div', { class: 'inspector-title' }, atom.label),
      h('div', { class: 'inspector-ref' }, reference(atom)),
      h('div', { class: 'inspector-actions' },
        button('Expand', { icon: 'expand', variant: 'small', title: 'Load incident hyperedges', onClick: () => this.actions.onExpand(atom) }),
        button('Query', { icon: 'console', variant: 'small', title: query, onClick: () => this.actions.onQuery(query) }),
        button('', { icon: 'copy', variant: 'small', title: 'Copy reference', onClick: () => this.copy(reference(atom)) }),
        pinned ? button('', { icon: 'pin', variant: 'small', title: 'Release pinned position', onClick: () => { this.view.release(atom.id); this.show(atom); } }) : null,
        button('', { icon: 'eyeOff', variant: 'small', title: 'Hide from view', onClick: () => this.view.remove(atom.id) })));
  }

  body(atom) {
    const properties = Object.entries(atom.properties ?? {});
    const sections = [
      h('div', { class: 'inspector-section' }, 'Overview'),
      h('dl', { class: 'kv' },
        h('dt', {}, 'Atom'), h('dd', { class: 'mono' }, String(atom.id)),
        atom.key ? [h('dt', {}, 'Key'), h('dd', {}, atom.key)] : null,
        h('dt', {}, 'Degree'), h('dd', {}, count(atom.degree)),
        isEdge(atom) ? [h('dt', {}, 'Cardinality'), h('dd', {}, count(atom.cardinality))] : null),
      h('div', { class: 'inspector-section' }, 'Properties'),
      properties.length
        ? h('dl', { class: 'kv' }, properties.map(([name, value]) => [h('dt', {}, name), h('dd', {}, displayValue(value))]))
        : h('div', { class: 'kv muted' }, 'No properties')
    ];
    if (isEdge(atom)) {
      const members = [...(atom.members ?? [])].sort((a, b) => a.position - b.position);
      sections.push(h('div', { class: 'inspector-section' },
        `Members · ${count(members.length)}${atom.truncated ? ` of ${count(atom.cardinality)}` : ''}`));
      sections.push(h('div', { class: 'member-list' }, members.map(member => this.member(atom, member))));
    }
    const containing = [...(this.view.memberOf.get(atom.id) ?? [])].map(id => this.view.atom(id)).filter(Boolean);
    if (containing.length) {
      sections.push(h('div', { class: 'inspector-section' }, `Member of · ${count(containing.length)} in view`));
      sections.push(h('div', { class: 'member-list' }, containing.map(edge => {
        const membership = (edge.members ?? []).find(member => member.atom === atom.id);
        const roles = edge.kind === 'ORDERED_EDGE' && membership ? [`#${membership.position}`, ...membership.roles] : membership?.roles ?? [];
        return this.entry(edge, edge.label, roles, membership?.weight);
      })));
    }
    return sections;
  }

  member(edge, member) {
    const target = this.view.atom(member.atom);
    const name = target ? target.label : `@${member.atom}`;
    const roles = edge.kind === 'ORDERED_EDGE' ? [`#${member.position}`, ...member.roles] : member.roles;
    return this.entry(target ?? { id: member.atom, typeId: 0, kind: 'NODE' }, name, roles, member.weight);
  }

  entry(atom, name, roles, weight) {
    const weighted = weight !== undefined && weight !== 1;
    const bar = h('span');
    bar.style.width = `${Math.round(Math.min(1, Math.max(0, weight ?? 1)) * 100)}%`;
    return h('button', { class: 'member', type: 'button', title: `@${atom.id}`, onClick: () => this.view.select(atom.id, { center: true }) },
      swatch(atom),
      h('div', {},
        h('div', { class: 'member-name' }, name),
        roles.length ? h('div', { class: 'member-roles' }, roles.map(role => h('span', { class: 'role' }, role))) : null),
      weighted ? h('div', { class: 'member-weight', title: `weight ${weight}` }, bar) : h('span'));
  }

  async copy(text) {
    try {
      await navigator.clipboard.writeText(text);
      toast(`Copied ${text}`);
    } catch {
      toast('Clipboard access was denied', 'error');
    }
  }
}
