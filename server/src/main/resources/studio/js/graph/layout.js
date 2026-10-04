const THETA_SQUARED = 0.81;
const MAX_DEPTH = 24;

class Quad {
  constructor(x, y, size) {
    this.x = x;
    this.y = y;
    this.size = size;
    this.body = null;
    this.children = null;
    this.charge = 0;
    this.cx = 0;
    this.cy = 0;
  }

  insert(body, depth = 0) {
    if (this.children === null && this.body === null) {
      this.body = body;
      return;
    }
    if (this.children === null) {
      if (depth >= MAX_DEPTH) {
        body.x += (Math.random() - 0.5) * 1e-3;
        body.y += (Math.random() - 0.5) * 1e-3;
        return;
      }
      const existing = this.body;
      this.body = null;
      const half = this.size / 2;
      this.children = [
        new Quad(this.x, this.y, half), new Quad(this.x + half, this.y, half),
        new Quad(this.x, this.y + half, half), new Quad(this.x + half, this.y + half, half)
      ];
      this.child(existing).insert(existing, depth + 1);
    }
    this.child(body).insert(body, depth + 1);
  }

  child(body) {
    const half = this.size / 2;
    return this.children[(body.x >= this.x + half ? 1 : 0) + (body.y >= this.y + half ? 2 : 0)];
  }

  accumulate() {
    if (this.children === null) {
      if (this.body) {
        this.charge = this.body.charge;
        this.cx = this.body.x;
        this.cy = this.body.y;
      }
      return;
    }
    let charge = 0;
    let weight = 0;
    let cx = 0;
    let cy = 0;
    for (const child of this.children) {
      child.accumulate();
      const magnitude = Math.abs(child.charge);
      charge += child.charge;
      weight += magnitude;
      cx += child.cx * magnitude;
      cy += child.cy * magnitude;
    }
    this.charge = charge;
    this.cx = weight ? cx / weight : this.x + this.size / 2;
    this.cy = weight ? cy / weight : this.y + this.size / 2;
  }

  repel(body, alpha) {
    if (this.charge === 0 || (this.children === null && (this.body === null || this.body === body))) {
      return;
    }
    let dx = this.cx - body.x;
    let dy = this.cy - body.y;
    let distance = dx * dx + dy * dy;
    if (this.children === null || (this.size * this.size) / Math.max(distance, 1e-6) < THETA_SQUARED) {
      if (distance === 0) {
        dx = (Math.random() - 0.5) * 1e-2;
        dy = (Math.random() - 0.5) * 1e-2;
        distance = dx * dx + dy * dy;
      }
      distance = Math.max(distance, 64);
      const force = (this.charge * alpha) / distance;
      body.vx += dx * force;
      body.vy += dy * force;
      return;
    }
    for (const child of this.children) {
      child.repel(body, alpha);
    }
  }
}

export class ForceLayout {
  constructor() {
    this.bodies = [];
    this.links = [];
    this.alpha = 1;
    this.alphaMin = 0.003;
    this.alphaTarget = 0;
    this.alphaDecay = 1 - Math.pow(this.alphaMin, 1 / 300);
    this.velocityDecay = 0.45;
    this.gravity = 0.035;
  }

  set(bodies, links) {
    this.bodies = bodies;
    this.links = links;
    const degree = new Map();
    for (const link of links) {
      degree.set(link.source, (degree.get(link.source) ?? 0) + 1);
      degree.set(link.target, (degree.get(link.target) ?? 0) + 1);
    }
    for (const link of links) {
      const sourceDegree = degree.get(link.source);
      link.bias = sourceDegree / (sourceDegree + degree.get(link.target));
    }
  }

  reheat(alpha = 0.8) {
    this.alpha = Math.max(this.alpha, alpha);
  }

  get running() {
    return this.alpha >= this.alphaMin || this.alphaTarget > 0;
  }

  step() {
    if (!this.running) {
      return false;
    }
    this.alpha += (this.alphaTarget - this.alpha) * this.alphaDecay;
    const alpha = this.alpha;
    for (const link of this.links) {
      const source = link.source;
      const target = link.target;
      let dx = target.x + target.vx - source.x - source.vx;
      let dy = target.y + target.vy - source.y - source.vy;
      let length = Math.hypot(dx, dy) || 1e-3;
      length = ((length - link.distance) / length) * alpha * link.strength;
      dx *= length;
      dy *= length;
      target.vx -= dx * link.bias;
      target.vy -= dy * link.bias;
      source.vx += dx * (1 - link.bias);
      source.vy += dy * (1 - link.bias);
    }
    if (this.bodies.length > 1) {
      let minX = Infinity;
      let minY = Infinity;
      let maxX = -Infinity;
      let maxY = -Infinity;
      for (const body of this.bodies) {
        minX = Math.min(minX, body.x);
        minY = Math.min(minY, body.y);
        maxX = Math.max(maxX, body.x);
        maxY = Math.max(maxY, body.y);
      }
      const root = new Quad(minX - 1, minY - 1, Math.max(maxX - minX, maxY - minY) + 2);
      for (const body of this.bodies) {
        root.insert(body);
      }
      root.accumulate();
      for (const body of this.bodies) {
        root.repel(body, alpha);
      }
    }
    for (const body of this.bodies) {
      body.vx -= body.x * this.gravity * alpha;
      body.vy -= body.y * this.gravity * alpha;
      if (body.fx !== null) {
        body.x = body.fx;
        body.y = body.fy;
        body.vx = 0;
        body.vy = 0;
      } else {
        body.vx *= 1 - this.velocityDecay;
        body.vy *= 1 - this.velocityDecay;
        body.x += body.vx;
        body.y += body.vy;
      }
    }
    return true;
  }
}
