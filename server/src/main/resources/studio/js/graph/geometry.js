// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

const SAMPLES = 10;
const UNIT = Array.from({ length: SAMPLES }, (_, index) => {
  const angle = (index / SAMPLES) * Math.PI * 2;
  return [Math.cos(angle), Math.sin(angle)];
});

function cross(origin, a, b) {
  return (a[0] - origin[0]) * (b[1] - origin[1]) - (a[1] - origin[1]) * (b[0] - origin[0]);
}

export function convexHull(points) {
  if (points.length < 3) {
    return points.slice();
  }
  const sorted = points.slice().sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const lower = [];
  for (const point of sorted) {
    while (lower.length >= 2 && cross(lower.at(-2), lower.at(-1), point) <= 0) {
      lower.pop();
    }
    lower.push(point);
  }
  const upper = [];
  for (let index = sorted.length - 1; index >= 0; index--) {
    const point = sorted[index];
    while (upper.length >= 2 && cross(upper.at(-2), upper.at(-1), point) <= 0) {
      upper.pop();
    }
    upper.push(point);
  }
  lower.pop();
  upper.pop();
  return lower.concat(upper);
}

export function paddedHull(circles) {
  const points = [];
  for (const { x, y, r } of circles) {
    for (const [cos, sin] of UNIT) {
      points.push([x + cos * r, y + sin * r]);
    }
  }
  return convexHull(points);
}

export function traceSmooth(context, polygon) {
  if (polygon.length < 3) {
    return;
  }
  const mid = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
  const start = mid(polygon.at(-1), polygon[0]);
  context.moveTo(start[0], start[1]);
  for (let index = 0; index < polygon.length; index++) {
    const point = polygon[index];
    const next = mid(point, polygon[(index + 1) % polygon.length]);
    context.quadraticCurveTo(point[0], point[1], next[0], next[1]);
  }
  context.closePath();
}

export function contains(polygon, x, y) {
  let inside = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const [xi, yi] = polygon[i];
    const [xj, yj] = polygon[j];
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) {
      inside = !inside;
    }
  }
  return inside;
}

export function area(polygon) {
  let total = 0;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    total += (polygon[j][0] + polygon[i][0]) * (polygon[j][1] - polygon[i][1]);
  }
  return Math.abs(total / 2);
}
