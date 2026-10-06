// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import { alpha, theme, typeColor } from '../palette.js';
import { area, contains, paddedHull, traceSmooth } from './geometry.js';
import { ForceLayout } from './layout.js';

const NODE_CHARGE = -420;
const HUB_CHARGE = -150;
const HULL_PADDING = 10;
const MIN_ZOOM = 0.06;
const MAX_ZOOM = 6;

const isEdge = atom => atom.kind !== 'NODE';
const clamp = (value, low, high) => Math.min(high, Math.max(low, value));
const truncate = (text, length) => (text.length > length ? `${text.slice(0, length - 1)}…` : text);

export class GraphView {
  constructor(host, { onSelect = () => {}, onExpand = () => {}, onChange = () => {} } = {}) {
    this.host = host;
    this.canvas = document.createElement('canvas');
    this.canvas.setAttribute('tabindex', '0');
    this.canvas.setAttribute('aria-label', 'Hypergraph view');
    host.append(this.canvas);
    this.context = this.canvas.getContext('2d');
    this.callbacks = { onSelect, onExpand, onChange };
    this.atoms = new Map();
    this.bodies = new Map();
    this.memberOf = new Map();
    this.hulls = new Map();
    this.hullsStale = true;
    this.layout = new ForceLayout();
    this.transform = { x: 0, y: 0, k: 1 };
    this.animation = null;
    this.mode = 'both';
    this.labels = true;
    this.hiddenTypes = new Set();
    this.selected = null;
    this.hovered = null;
    this.pointer = null;
    this.size = { width: 0, height: 0 };
    this.dpr = window.devicePixelRatio || 1;
    this.colors = theme();
    this.pendingFit = false;
    this.autoFit = false;
    this.frame = 0;
    this.dirty = true;
    this.tick = this.tick.bind(this);
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(host);
    this.media = window.matchMedia('(prefers-color-scheme: dark)');
    this.onTheme = () => {
      this.colors = theme();
      this.invalidate();
    };
    this.media.addEventListener('change', this.onTheme);
    window.addEventListener('hstore:theme', this.onTheme);
    this.bindPointer();
  }

  destroy() {
    cancelAnimationFrame(this.frame);
    this.resizeObserver.disconnect();
    this.media.removeEventListener('change', this.onTheme);
    window.removeEventListener('hstore:theme', this.onTheme);
    this.canvas.remove();
  }

  get empty() {
    return this.atoms.size === 0;
  }

  atom(id) {
    return this.atoms.get(id);
  }

  load(payload, { anchor = null, replace = false } = {}) {
    const fresh = this.atoms.size === 0 || replace;
    if (replace) {
      const incoming = new Set(payload.atoms.map(atom => atom.id));
      for (const id of [...this.atoms.keys()]) {
        if (!incoming.has(id)) {
          this.atoms.delete(id);
          this.bodies.delete(id);
        }
      }
      if (this.selected !== null && !incoming.has(this.selected)) {
        this.selected = null;
        this.callbacks.onSelect(null);
      }
    }
    const created = [];
    for (const atom of payload.atoms) {
      this.atoms.set(atom.id, atom);
      if (!this.bodies.has(atom.id)) {
        created.push(atom);
      }
    }
    this.place(created, anchor);
    for (const atom of payload.atoms) {
      this.bodies.get(atom.id).r = this.radius(atom);
    }
    this.rebuild();
    if (fresh && created.length === payload.atoms.length) {
      this.warmUp();
      this.pendingFit = true;
      this.autoFit = true;
      this.fitWhenSized(false);
    } else {
      this.layout.reheat(created.length ? 0.55 : 0.2);
    }
    this.schedule();
    this.callbacks.onChange();
    if (this.selected !== null) {
      this.callbacks.onSelect(this.atoms.get(this.selected) ?? null);
    }
  }

  place(atoms, anchor) {
    const origin = anchor !== null ? this.bodies.get(anchor) : null;
    const count = this.bodies.size;
    atoms.forEach((atom, index) => {
      let x;
      let y;
      if (origin) {
        const angle = Math.random() * Math.PI * 2;
        const distance = 50 + Math.random() * 60;
        x = origin.x + Math.cos(angle) * distance;
        y = origin.y + Math.sin(angle) * distance;
      } else {
        const radius = 18 * Math.sqrt(count + index + 0.5);
        const angle = (count + index) * Math.PI * (3 - Math.sqrt(5));
        x = radius * Math.cos(angle);
        y = radius * Math.sin(angle);
      }
      this.bodies.set(atom.id, { id: atom.id, x, y, vx: 0, vy: 0, fx: null, fy: null, r: this.radius(atom), charge: isEdge(atom) ? HUB_CHARGE : NODE_CHARGE });
    });
    for (const atom of atoms.filter(isEdge)) {
      const members = (atom.members ?? []).map(member => this.bodies.get(member.atom)).filter(Boolean);
      if (members.length) {
        const body = this.bodies.get(atom.id);
        body.x = members.reduce((sum, member) => sum + member.x, 0) / members.length + (Math.random() - 0.5) * 8;
        body.y = members.reduce((sum, member) => sum + member.y, 0) / members.length + (Math.random() - 0.5) * 8;
      }
    }
  }

  radius(atom) {
    return isEdge(atom)
      ? 4.5 + Math.min(6, Math.log2(1 + (atom.cardinality ?? 0)) * 1.3)
      : 9 + Math.min(9, Math.log2(1 + (atom.degree ?? 0)) * 2);
  }

  visible(atom) {
    return atom !== undefined && !this.hiddenTypes.has(atom.typeId);
  }

  rebuild() {
    this.memberOf.clear();
    const bodies = [];
    const links = [];
    for (const atom of this.atoms.values()) {
      if (!this.visible(atom)) {
        continue;
      }
      bodies.push(this.bodies.get(atom.id));
      if (!isEdge(atom)) {
        continue;
      }
      const members = (atom.members ?? []).filter(member => this.visible(this.atoms.get(member.atom)));
      const hub = this.bodies.get(atom.id);
      for (const member of members) {
        if (!this.memberOf.has(member.atom)) {
          this.memberOf.set(member.atom, new Set());
        }
        this.memberOf.get(member.atom).add(atom.id);
        links.push({
          source: hub,
          target: this.bodies.get(member.atom),
          distance: 30 + 9 * Math.sqrt(members.length),
          strength: 0.85 / Math.sqrt(members.length)
        });
      }
    }
    this.layout.set(bodies, links);
    this.hullsStale = true;
    this.invalidate();
  }

  warmUp() {
    const steps = clamp(Math.round(40000 / Math.max(1, this.layout.bodies.length)), 40, 220);
    this.layout.alpha = 1;
    for (let step = 0; step < steps; step++) {
      this.layout.step();
    }
    this.hullsStale = true;
  }

  remove(id) {
    this.atoms.delete(id);
    this.bodies.delete(id);
    if (this.selected === id) {
      this.selected = null;
      this.callbacks.onSelect(null);
    }
    if (this.hovered === id) {
      this.hovered = null;
    }
    this.rebuild();
    this.layout.reheat(0.25);
    this.schedule();
    this.callbacks.onChange();
  }

  clear() {
    this.atoms.clear();
    this.bodies.clear();
    this.selected = null;
    this.hovered = null;
    this.rebuild();
    this.callbacks.onSelect(null);
    this.callbacks.onChange();
  }

  select(id, { center = false } = {}) {
    this.selected = id;
    if (center && id !== null && this.bodies.has(id)) {
      const body = this.bodies.get(id);
      const k = Math.max(this.transform.k, 1);
      this.animateTo({ x: this.size.width / 2 - body.x * k, y: this.size.height / 2 - body.y * k, k });
    }
    this.callbacks.onSelect(id === null ? null : this.atoms.get(id) ?? null);
    this.invalidate();
  }

  release(id) {
    const body = this.bodies.get(id);
    if (body) {
      body.fx = null;
      body.fy = null;
      body.pinned = false;
      this.layout.reheat(0.3);
      this.schedule();
    }
  }

  pinned(id) {
    return Boolean(this.bodies.get(id)?.pinned);
  }

  setMode(mode) {
    this.mode = mode;
    this.invalidate();
  }

  setLabels(enabled) {
    this.labels = enabled;
    this.invalidate();
  }

  toggleType(typeId) {
    if (this.hiddenTypes.has(typeId)) {
      this.hiddenTypes.delete(typeId);
    } else {
      this.hiddenTypes.add(typeId);
    }
    this.rebuild();
    this.layout.reheat(0.3);
    this.schedule();
    this.callbacks.onChange();
  }

  summary() {
    const types = new Map();
    for (const atom of this.atoms.values()) {
      const entry = types.get(atom.typeId) ?? { typeId: atom.typeId, type: atom.type, kind: atom.kind, count: 0, hidden: this.hiddenTypes.has(atom.typeId) };
      entry.count++;
      types.set(atom.typeId, entry);
    }
    return [...types.values()].sort((a, b) => (a.kind === 'NODE') - (b.kind === 'NODE') || b.count - a.count);
  }

  counts() {
    let nodes = 0;
    let edges = 0;
    for (const atom of this.atoms.values()) {
      if (isEdge(atom)) {
        edges++;
      } else {
        nodes++;
      }
    }
    return { nodes, edges };
  }

  reheat() {
    for (const body of this.bodies.values()) {
      body.vx += (Math.random() - 0.5) * 6;
      body.vy += (Math.random() - 0.5) * 6;
    }
    this.layout.reheat(0.9);
    this.schedule();
  }

  zoomBy(factor) {
    this.zoomAt(this.size.width / 2, this.size.height / 2, factor, true);
  }

  zoomAt(sx, sy, factor, animate = false) {
    this.autoFit = false;
    const { x, y, k } = this.animation?.target ?? this.transform;
    const next = clamp(k * factor, MIN_ZOOM, MAX_ZOOM);
    const target = { k: next, x: sx - ((sx - x) / k) * next, y: sy - ((sy - y) / k) * next };
    if (animate) {
      this.animateTo(target);
    } else {
      this.animation = null;
      this.transform = target;
      this.invalidate();
    }
  }

  fit(animate = true) {
    const bodies = this.layout.bodies;
    if (!bodies.length || !this.size.width) {
      return;
    }
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const body of bodies) {
      minX = Math.min(minX, body.x - body.r - HULL_PADDING);
      minY = Math.min(minY, body.y - body.r - HULL_PADDING);
      maxX = Math.max(maxX, body.x + body.r + HULL_PADDING);
      maxY = Math.max(maxY, body.y + body.r + HULL_PADDING);
    }
    const padding = 48;
    const k = clamp(Math.min((this.size.width - padding * 2) / (maxX - minX || 1), (this.size.height - padding * 2) / (maxY - minY || 1)), 0.08, 1.8);
    const target = { k, x: this.size.width / 2 - ((minX + maxX) / 2) * k, y: this.size.height / 2 - ((minY + maxY) / 2) * k };
    if (animate) {
      this.animateTo(target);
    } else {
      this.transform = target;
      this.invalidate();
    }
  }

  fitWhenSized(animate) {
    if (this.size.width && this.pendingFit) {
      this.pendingFit = false;
      this.fit(animate);
    }
  }

  animateTo(target) {
    this.animation = { from: { ...this.transform }, target, start: performance.now(), duration: 320 };
    this.schedule();
  }

  resize() {
    const width = this.host.clientWidth;
    const height = this.host.clientHeight;
    this.dpr = window.devicePixelRatio || 1;
    if (width === this.size.width && height === this.size.height) {
      return;
    }
    const previous = this.size;
    this.size = { width, height };
    this.canvas.width = Math.round(width * this.dpr);
    this.canvas.height = Math.round(height * this.dpr);
    if (previous.width && width) {
      this.transform.x += (width - previous.width) / 2;
      this.transform.y += (height - previous.height) / 2;
    }
    this.fitWhenSized(false);
    this.invalidate();
  }

  invalidate() {
    this.dirty = true;
    this.schedule();
  }

  schedule() {
    if (!this.frame) {
      this.frame = requestAnimationFrame(this.tick);
    }
  }

  tick(now) {
    this.frame = 0;
    const moved = this.layout.step();
    if (moved) {
      this.hullsStale = true;
      if (this.autoFit && this.layout.alpha < 0.08) {
        this.autoFit = false;
        this.fit(true);
      }
    }
    const animated = this.animation !== null;
    let animating = false;
    if (this.animation) {
      const { from, target, start, duration } = this.animation;
      const progress = clamp((now - start) / duration, 0, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      this.transform = {
        x: from.x + (target.x - from.x) * eased,
        y: from.y + (target.y - from.y) * eased,
        k: from.k + (target.k - from.k) * eased
      };
      animating = progress < 1;
      if (!animating) {
        this.animation = null;
      }
    }
    if (moved || animated || this.dirty) {
      this.draw();
      this.dirty = false;
    }
    if (this.layout.running || animating) {
      this.schedule();
    }
  }

  toWorld(sx, sy) {
    const { x, y, k } = this.transform;
    return { x: (sx - x) / k, y: (sy - y) / k };
  }

  hubVisible(atom, focus) {
    return this.mode !== 'hulls' || (focus?.has(atom.id) ?? false) || this.memberOf.has(atom.id);
  }

  hit(sx, sy, { hulls = true } = {}) {
    const point = this.toWorld(sx, sy);
    const slack = 3 / this.transform.k;
    let best = null;
    let bestDistance = Infinity;
    for (const body of this.layout.bodies) {
      const atom = this.atoms.get(body.id);
      if (isEdge(atom) && !this.hubVisible(atom, null)) {
        continue;
      }
      const distance = Math.hypot(body.x - point.x, body.y - point.y);
      if (distance <= body.r + slack && distance < bestDistance) {
        best = body.id;
        bestDistance = distance;
      }
    }
    if (best !== null || !hulls || this.mode === 'hubs') {
      return best;
    }
    let smallest = Infinity;
    for (const [id, polygon] of this.hulls) {
      if (contains(polygon, point.x, point.y)) {
        const size = area(polygon);
        if (size < smallest) {
          smallest = size;
          best = id;
        }
      }
    }
    return best;
  }

  bindPointer() {
    const canvas = this.canvas;
    const position = event => {
      const box = canvas.getBoundingClientRect();
      return { x: event.clientX - box.left, y: event.clientY - box.top };
    };
    canvas.addEventListener('pointerdown', event => {
      if (event.button !== 0) {
        return;
      }
      canvas.setPointerCapture(event.pointerId);
      const at = position(event);
      const id = this.hit(at.x, at.y, { hulls: false });
      this.pointer = { start: at, last: at, body: id === null ? null : this.bodies.get(id), moved: false };
      canvas.classList.add('dragging');
    });
    canvas.addEventListener('pointermove', event => {
      const at = position(event);
      if (!this.pointer) {
        const id = this.hit(at.x, at.y);
        if (id !== this.hovered) {
          this.hovered = id;
          canvas.classList.toggle('pointing', id !== null);
          this.invalidate();
        }
        return;
      }
      const pointer = this.pointer;
      if (!pointer.moved && Math.hypot(at.x - pointer.start.x, at.y - pointer.start.y) > 3) {
        pointer.moved = true;
      }
      if (pointer.moved && pointer.body) {
        const world = this.toWorld(at.x, at.y);
        pointer.body.fx = world.x;
        pointer.body.fy = world.y;
        this.layout.alphaTarget = 0.25;
        this.layout.reheat(0.25);
        this.schedule();
      } else if (pointer.moved) {
        this.autoFit = false;
        this.animation = null;
        this.transform.x += at.x - pointer.last.x;
        this.transform.y += at.y - pointer.last.y;
        this.invalidate();
      }
      pointer.last = at;
    });
    const finish = event => {
      const pointer = this.pointer;
      if (!pointer) {
        return;
      }
      this.pointer = null;
      canvas.classList.remove('dragging');
      this.layout.alphaTarget = 0;
      if (pointer.moved && pointer.body) {
        pointer.body.pinned = true;
        this.invalidate();
        return;
      }
      if (!pointer.moved && event.type === 'pointerup') {
        const at = position(event);
        this.select(this.hit(at.x, at.y));
      }
    };
    canvas.addEventListener('pointerup', finish);
    canvas.addEventListener('pointercancel', finish);
    canvas.addEventListener('pointerleave', () => {
      if (!this.pointer && this.hovered !== null) {
        this.hovered = null;
        this.invalidate();
      }
    });
    canvas.addEventListener('dblclick', event => {
      const at = position(event);
      const id = this.hit(at.x, at.y);
      if (id !== null) {
        this.callbacks.onExpand(this.atoms.get(id));
      }
    });
    canvas.addEventListener('wheel', event => {
      event.preventDefault();
      const at = position(event);
      const delta = event.deltaMode === 1 ? event.deltaY * 16 : event.deltaY;
      this.zoomAt(at.x, at.y, Math.exp(-delta * (event.ctrlKey ? 0.01 : 0.0018)));
    }, { passive: false });
    canvas.addEventListener('keydown', event => {
      const actions = { '+': () => this.zoomBy(1.3), '=': () => this.zoomBy(1.3), '-': () => this.zoomBy(1 / 1.3), f: () => this.fit(), Escape: () => this.select(null) };
      const action = actions[event.key];
      if (action) {
        event.preventDefault();
        action();
      }
    });
  }

  focusSet() {
    const center = this.hovered ?? this.selected;
    const atom = center === null ? undefined : this.atoms.get(center);
    if (!atom) {
      return null;
    }
    const focus = new Set([center]);
    const addEdge = edge => {
      focus.add(edge.id);
      for (const member of edge.members ?? []) {
        focus.add(member.atom);
      }
    };
    if (isEdge(atom)) {
      addEdge(atom);
    }
    for (const edgeId of this.memberOf.get(center) ?? []) {
      const edge = this.atoms.get(edgeId);
      if (isEdge(atom)) {
        focus.add(edgeId);
      } else {
        addEdge(edge);
      }
    }
    return focus;
  }

  refreshHulls() {
    this.hulls.clear();
    if (this.mode === 'hubs') {
      return;
    }
    for (const atom of this.atoms.values()) {
      if (!isEdge(atom) || !this.visible(atom)) {
        continue;
      }
      const circles = [];
      for (const member of atom.members ?? []) {
        const body = this.bodies.get(member.atom);
        if (body && this.visible(this.atoms.get(member.atom))) {
          circles.push({ x: body.x, y: body.y, r: body.r + HULL_PADDING });
        }
      }
      if (circles.length < 2) {
        continue;
      }
      const hub = this.bodies.get(atom.id);
      circles.push({ x: hub.x, y: hub.y, r: hub.r + HULL_PADDING * 0.5 });
      this.hulls.set(atom.id, paddedHull(circles));
    }
    this.hullsStale = false;
  }

  draw(context = this.context, { width = this.size.width, height = this.size.height, dpr = this.dpr, background = null } = {}) {
    if (!width || !height) {
      return;
    }
    if (this.hullsStale) {
      this.refreshHulls();
    }
    const { x, y, k } = this.transform;
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.clearRect(0, 0, width * dpr, height * dpr);
    if (background) {
      context.fillStyle = background;
      context.fillRect(0, 0, width * dpr, height * dpr);
    }
    context.setTransform(dpr * k, 0, 0, dpr * k, dpr * x, dpr * y);
    const focus = this.focusSet();
    const dim = id => focus !== null && !focus.has(id);
    this.drawHulls(context, focus, dim, k);
    this.drawSpokes(context, focus, dim, k);
    this.drawHubs(context, focus, dim, k);
    this.drawNodes(context, dim, k);
    context.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.drawLabels(context, focus, dim);
  }

  drawHulls(context, focus, dim, k) {
    if (this.mode === 'hubs') {
      return;
    }
    const ordered = [...this.hulls.entries()].map(([id, polygon]) => ({ id, polygon, size: area(polygon) })).sort((a, b) => b.size - a.size);
    const density = clamp(24 / Math.max(1, ordered.length), 0.2, 1);
    for (const { id, polygon } of ordered) {
      const atom = this.atoms.get(id);
      const color = typeColor(atom.typeId);
      const emphasised = focus?.has(id) && (id === this.selected || id === this.hovered || !isEdge(this.atoms.get(this.hovered ?? this.selected)));
      context.beginPath();
      traceSmooth(context, polygon);
      context.fillStyle = alpha(color, dim(id) ? 0.02 : emphasised ? 0.17 : 0.075 * density);
      context.fill();
      context.lineWidth = (emphasised ? 1.8 : 1.1) / k;
      context.strokeStyle = alpha(color, dim(id) ? 0.08 : emphasised ? 0.85 : 0.42 * density);
      context.stroke();
    }
  }

  drawSpokes(context, focus, dim, k) {
    for (const atom of this.atoms.values()) {
      if (!isEdge(atom) || !this.visible(atom)) {
        continue;
      }
      const showSpokes = this.mode !== 'hulls' || focus?.has(atom.id);
      const hub = this.bodies.get(atom.id);
      const color = typeColor(atom.typeId);
      const faded = dim(atom.id);
      const members = (atom.members ?? []).filter(member => this.bodies.has(member.atom) && this.visible(this.atoms.get(member.atom)));
      if (showSpokes) {
        for (const member of members) {
          const target = this.bodies.get(member.atom);
          const higherOrder = isEdge(this.atoms.get(member.atom));
          context.beginPath();
          context.setLineDash(higherOrder ? [5 / k, 4 / k] : []);
          context.moveTo(hub.x, hub.y);
          context.lineTo(target.x, target.y);
          context.lineWidth = (0.9 + 1.6 * clamp(member.weight ?? 1, 0, 1)) / k;
          context.strokeStyle = alpha(color, faded || dim(member.atom) ? 0.08 : atom.kind === 'ORDERED_EDGE' ? 0.3 : 0.62);
          context.stroke();
        }
        context.setLineDash([]);
      }
      if (atom.kind === 'ORDERED_EDGE' && members.length > 1) {
        this.drawChain(context, members, color, faded, k);
      }
    }
  }

  drawChain(context, members, color, faded, k) {
    const sequence = [...members].sort((a, b) => a.position - b.position).map(member => this.bodies.get(member.atom));
    context.strokeStyle = alpha(color, faded ? 0.12 : 0.9);
    context.fillStyle = context.strokeStyle;
    context.lineWidth = 1.8 / k;
    for (let index = 1; index < sequence.length; index++) {
      const from = sequence[index - 1];
      const to = sequence[index];
      const angle = Math.atan2(to.y - from.y, to.x - from.x);
      const length = Math.hypot(to.x - from.x, to.y - from.y);
      if (length < from.r + to.r + 2) {
        continue;
      }
      const start = { x: from.x + Math.cos(angle) * (from.r + 2 / k), y: from.y + Math.sin(angle) * (from.r + 2 / k) };
      const end = { x: to.x - Math.cos(angle) * (to.r + 3 / k), y: to.y - Math.sin(angle) * (to.r + 3 / k) };
      const bend = Math.min(18, length * 0.12);
      const control = { x: (start.x + end.x) / 2 - Math.sin(angle) * bend, y: (start.y + end.y) / 2 + Math.cos(angle) * bend };
      context.beginPath();
      context.moveTo(start.x, start.y);
      context.quadraticCurveTo(control.x, control.y, end.x, end.y);
      context.stroke();
      const head = Math.atan2(end.y - control.y, end.x - control.x);
      const size = 7 / k;
      context.beginPath();
      context.moveTo(end.x, end.y);
      context.lineTo(end.x - Math.cos(head - 0.42) * size, end.y - Math.sin(head - 0.42) * size);
      context.lineTo(end.x - Math.cos(head + 0.42) * size, end.y - Math.sin(head + 0.42) * size);
      context.closePath();
      context.fill();
    }
  }

  drawHubs(context, focus, dim, k) {
    for (const atom of this.atoms.values()) {
      if (!isEdge(atom) || !this.visible(atom) || !this.hubVisible(atom, focus)) {
        continue;
      }
      const body = this.bodies.get(atom.id);
      const color = typeColor(atom.typeId);
      context.globalAlpha = dim(atom.id) ? 0.2 : 1;
      context.beginPath();
      if (atom.kind === 'ORDERED_EDGE') {
        const side = body.r * 1.6;
        context.roundRect(body.x - side / 2, body.y - side / 2, side, side, side * 0.28);
      } else {
        context.moveTo(body.x, body.y - body.r * 1.25);
        context.lineTo(body.x + body.r * 1.25, body.y);
        context.lineTo(body.x, body.y + body.r * 1.25);
        context.lineTo(body.x - body.r * 1.25, body.y);
        context.closePath();
      }
      context.fillStyle = color;
      context.fill();
      context.lineWidth = 1.5 / k;
      context.strokeStyle = this.colors.canvas;
      context.stroke();
      if (atom.id === this.selected || atom.id === this.hovered) {
        this.ring(context, body, k, atom.id === this.selected);
      }
      if (body.pinned) {
        this.pin(context, body, k);
      }
      if (atom.truncated) {
        context.beginPath();
        context.arc(body.x, body.y, body.r * 1.25 + 4 / k, -0.6, 0.6);
        context.strokeStyle = alpha(color, 0.9);
        context.setLineDash([2 / k, 2 / k]);
        context.stroke();
        context.setLineDash([]);
      }
    }
    context.globalAlpha = 1;
  }

  drawNodes(context, dim, k) {
    for (const atom of this.atoms.values()) {
      if (isEdge(atom) || !this.visible(atom)) {
        continue;
      }
      const body = this.bodies.get(atom.id);
      context.globalAlpha = dim(atom.id) ? 0.22 : 1;
      context.beginPath();
      context.arc(body.x, body.y, body.r, 0, Math.PI * 2);
      context.fillStyle = typeColor(atom.typeId);
      context.fill();
      context.lineWidth = 2 / k;
      context.strokeStyle = this.colors.canvas;
      context.stroke();
      if (atom.id === this.selected || atom.id === this.hovered) {
        this.ring(context, body, k, atom.id === this.selected);
      }
      if (body.pinned) {
        this.pin(context, body, k);
      }
    }
    context.globalAlpha = 1;
  }

  ring(context, body, k, selected) {
    context.beginPath();
    context.arc(body.x, body.y, body.r * (isEdge(this.atoms.get(body.id)) ? 1.25 : 1) + 4 / k, 0, Math.PI * 2);
    context.lineWidth = (selected ? 2.5 : 1.5) / k;
    context.strokeStyle = selected ? this.colors.accent : alpha(this.colors.text.startsWith('#') ? this.colors.text : '#888888', 0.35);
    context.stroke();
  }

  pin(context, body, k) {
    context.beginPath();
    context.arc(body.x + body.r * 0.75, body.y - body.r * 0.75, 2.6 / k, 0, Math.PI * 2);
    context.fillStyle = this.colors.text;
    context.fill();
  }

  drawLabels(context, focus, dim) {
    const { x, y, k } = this.transform;
    const font = this.colors.font || 'system-ui';
    context.textAlign = 'center';
    context.textBaseline = 'top';
    context.lineJoin = 'round';
    const label = (text, sx, sy, { weight = 500, size = 11.5, color = this.colors.text, faded = false } = {}) => {
      context.font = `${weight} ${size}px ${font}`;
      context.globalAlpha = faded ? 0.25 : 1;
      context.lineWidth = 3.5;
      context.strokeStyle = this.colors.canvas;
      context.strokeText(text, sx, sy);
      context.fillStyle = color;
      context.fillText(text, sx, sy);
    };
    for (const atom of this.atoms.values()) {
      if (!this.visible(atom)) {
        continue;
      }
      const body = this.bodies.get(atom.id);
      const sx = body.x * k + x;
      const sy = body.y * k + y;
      if (sx < -100 || sy < -40 || sx > this.size.width + 100 || sy > this.size.height + 40) {
        continue;
      }
      const emphasised = focus?.has(atom.id) || atom.id === this.selected;
      if (isEdge(atom)) {
        if (this.hubVisible(atom, focus) && (k > 1.15 || atom.id === this.hovered || atom.id === this.selected)) {
          label(`${atom.type} · ${atom.cardinality}`, sx, sy + body.r * 1.25 * k + 5, { weight: 600, size: 10.5, color: typeColor(atom.typeId), faded: dim(atom.id) });
        }
      } else if (this.labels && (k > 0.5 || emphasised)) {
        label(truncate(atom.label, k > 1.4 ? 40 : 24), sx, sy + body.r * k + 4, { faded: dim(atom.id) });
      }
    }
    const center = this.hovered ?? this.selected;
    const edges = center === null ? [] : isEdge(this.atoms.get(center)) ? [center] : k > 0.9 ? [...(this.memberOf.get(center) ?? [])] : [];
    const only = center !== null && !isEdge(this.atoms.get(center)) ? center : null;
    for (const edgeId of edges) {
      this.drawRoles(context, this.atoms.get(edgeId), label, only);
    }
    context.globalAlpha = 1;
  }

  drawRoles(context, edge, label, only) {
    if (!edge || !this.visible(edge)) {
      return;
    }
    const { x, y, k } = this.transform;
    const hub = this.bodies.get(edge.id);
    for (const member of edge.members ?? []) {
      const body = this.bodies.get(member.atom);
      if (!body || !this.visible(this.atoms.get(member.atom)) || (only !== null && member.atom !== only)) {
        continue;
      }
      const parts = [];
      if (member.roles?.length) {
        parts.push(member.roles.join(' | '));
      }
      if (edge.kind === 'ORDERED_EDGE') {
        parts.push(`#${member.position}`);
      }
      if (member.weight !== undefined && member.weight !== 1) {
        parts.push(`w ${Math.round(member.weight * 100) / 100}`);
      }
      if (!parts.length) {
        continue;
      }
      const mx = (hub.x * 0.45 + body.x * 0.55) * k + x;
      const my = (hub.y * 0.45 + body.y * 0.55) * k + y - 6;
      label(parts.join(' · '), mx, my, { weight: 600, size: 10.5, color: typeColor(edge.typeId) });
    }
  }

  exportPng(name = 'hstore-graph.png') {
    const canvas = document.createElement('canvas');
    canvas.width = this.canvas.width;
    canvas.height = this.canvas.height;
    this.draw(canvas.getContext('2d'), { background: this.colors.canvas });
    canvas.toBlob(blob => {
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = name;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  }
}
