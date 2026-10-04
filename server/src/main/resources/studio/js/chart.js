import { alpha, theme } from './palette.js';

export class SeriesChart {
  constructor(host, { color, format = String, capacity = 150 }) {
    this.host = host;
    this.color = color;
    this.format = format;
    this.capacity = capacity;
    this.values = [];
    this.canvas = document.createElement('canvas');
    host.append(this.canvas);
    this.observer = new ResizeObserver(() => this.draw());
    this.observer.observe(host);
  }

  push(value) {
    this.values.push(value);
    if (this.values.length > this.capacity) {
      this.values.shift();
    }
    this.draw();
  }

  draw() {
    const width = this.host.clientWidth;
    const height = this.host.clientHeight;
    if (!width || !height) {
      return;
    }
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = Math.round(width * dpr);
    this.canvas.height = Math.round(height * dpr);
    const context = this.canvas.getContext('2d');
    context.setTransform(dpr, 0, 0, dpr, 0, 0);
    context.clearRect(0, 0, width, height);
    const colors = theme();
    const top = 12;
    const bottom = height - 20;
    const left = 12;
    const right = width - 12;
    const max = Math.max(1e-9, ...this.values) * 1.15;
    context.strokeStyle = colors.border;
    context.globalAlpha = 0.6;
    context.lineWidth = 1;
    context.setLineDash([3, 4]);
    for (const fraction of [0, 0.5, 1]) {
      const y = Math.round(top + (bottom - top) * fraction) + 0.5;
      context.beginPath();
      context.moveTo(left, y);
      context.lineTo(right, y);
      context.stroke();
    }
    context.setLineDash([]);
    context.globalAlpha = 1;
    context.fillStyle = colors.muted;
    context.font = `11px ${colors.font}`;
    context.textBaseline = 'bottom';
    context.textAlign = 'right';
    context.fillText(this.format(max / 1.15), right, top - 1);
    context.textAlign = 'left';
    context.textBaseline = 'top';
    context.fillText(`last ${this.capacity * 2}s`, left, bottom + 5);
    if (this.values.length < 2) {
      return;
    }
    const step = (right - left) / (this.capacity - 1);
    const offset = right - (this.values.length - 1) * step;
    const point = (value, index) => [offset + index * step, bottom - (value / max) * (bottom - top)];
    context.beginPath();
    this.values.forEach((value, index) => {
      const [x, y] = point(value, index);
      if (index === 0) {
        context.moveTo(x, y);
      } else {
        context.lineTo(x, y);
      }
    });
    const gradient = context.createLinearGradient(0, top, 0, bottom);
    gradient.addColorStop(0, alpha(this.color, 0.28));
    gradient.addColorStop(1, alpha(this.color, 0));
    context.lineWidth = 1.8;
    context.lineJoin = 'round';
    context.strokeStyle = this.color;
    context.stroke();
    context.lineTo(right, bottom);
    context.lineTo(offset, bottom);
    context.closePath();
    context.fillStyle = gradient;
    context.fill();
    const [x, y] = point(this.values.at(-1), this.values.length - 1);
    context.beginPath();
    context.arc(x, y, 3, 0, Math.PI * 2);
    context.fillStyle = this.color;
    context.fill();
  }

  destroy() {
    this.observer.disconnect();
  }
}
