import { cubicOut } from 'svelte/easing';
import * as t from 'svelte/transition';
import { flip as svelteFlip } from 'svelte/animate';

/** Анимации интерфейса: 150–250 мс, cubic-out, выключаются при prefers-reduced-motion. */
export const DURATION = 200;

function reduced(): boolean {
	return (
		typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches
	);
}

const d = (ms?: number) => (reduced() ? 0 : (ms ?? DURATION));

export function fade(node: Element, p: t.FadeParams = {}) {
	return t.fade(node, { easing: cubicOut, ...p, duration: d(p.duration ?? 160) });
}

export function fly(node: Element, p: t.FlyParams = {}) {
	return t.fly(node, { y: 8, easing: cubicOut, ...p, duration: d(p.duration) });
}

export function scale(node: Element, p: t.ScaleParams = {}) {
	return t.scale(node, { start: 0.96, easing: cubicOut, ...p, duration: d(p.duration ?? 180) });
}

export function slide(node: Element, p: t.SlideParams = {}) {
	return t.slide(node, { easing: cubicOut, ...p, duration: d(p.duration) });
}

export function flip(node: Element, state: { from: DOMRect; to: DOMRect }, p = {}) {
	return svelteFlip(node, state, { easing: cubicOut, duration: d(220), ...p });
}

/** Каскадное появление карточек: задержка по индексу, не больше 8 шагов. */
export const stagger = (i: number, step = 30) => (reduced() ? 0 : Math.min(i, 8) * step);

export const [send, receive] = t.crossfade({
	duration: () => d(220),
	easing: cubicOut,
	fallback: (node) => t.fade(node, { duration: d(160) })
});
