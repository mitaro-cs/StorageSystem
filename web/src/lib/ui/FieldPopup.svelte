<script lang="ts">
	import { tick, type Snippet } from 'svelte';
	import { scale } from '$lib/motion';
	import { placeField } from './menuPlace';

	// Всплывающая часть поля (список выбора, календарь, время) – в верхнем слое страницы, как меню:
	// её не обрезают карточки и окна с прокруткой, у нижнего края экрана она открывается вверх.
	let {
		open = $bindable(),
		anchor,
		label,
		role = 'group',
		width = 0,
		children
	}: {
		open: boolean;
		anchor: HTMLElement | undefined;
		label: string;
		/** group – календарь и часы (не «окно»: окно сайта остаётся одно), listbox – список выбора. */
		role?: 'group' | 'listbox';
		/** Ширина не меньше этой (для списка – ширина поля). */
		width?: number;
		children: Snippet;
	} = $props();

	let popup: HTMLDivElement | undefined = $state();
	let pos = $state({ top: 0, left: 0, up: false });
	let placed = $state(false);

	function place() {
		if (!popup || !anchor) return;
		const a = anchor.getBoundingClientRect();
		if (a.bottom < 0 || a.top > innerHeight) {
			open = false;
			return;
		}
		pos = placeField(
			a,
			{ width: popup.offsetWidth, height: popup.offsetHeight },
			{ width: innerWidth, height: innerHeight }
		);
		placed = true;
	}

	$effect(() => {
		if (!open) {
			placed = false;
			return;
		}
		tick().then(() => {
			try {
				popup?.showPopover?.();
			} catch {
				/* без Popover API – position: fixed */
			}
			place();
		});
	});

	function outside(e: PointerEvent) {
		if (!open) return;
		const t = e.target as Node;
		if (!popup?.contains(t) && !anchor?.contains(t)) open = false;
	}
</script>

<svelte:window
	onpointerdown={outside}
	onresize={place}
	onscrollcapture={(e) => {
		if (open && !popup?.contains(e.target as Node)) place();
	}}
	onkeydowncapture={(e) => {
		// Раньше окна (<dialog>): Esc закрывает только календарь или список, а не всё окно формы.
		if (open && e.key === 'Escape') {
			e.preventDefault();
			e.stopPropagation();
			open = false;
			(anchor?.matches('input, button')
				? anchor
				: anchor?.querySelector<HTMLElement>('input, button')
			)?.focus();
		}
	}}
/>

{#if open}
	<div
		class="field-popup"
		class:up={pos.up}
		{role}
		aria-label={label}
		popover="manual"
		bind:this={popup}
		style:top="{pos.top}px"
		style:left="{pos.left}px"
		style:min-width={width ? `${width}px` : undefined}
		style:visibility={placed ? 'visible' : 'hidden'}
		transition:scale={{ duration: 140, start: 0.96 }}
	>
		{@render children()}
	</div>
{/if}

<style>
	.field-popup {
		position: fixed;
		inset: auto;
		margin: 0;
		z-index: 1001;
		max-width: calc(100vw - 16px);
		max-height: min(420px, calc(100vh - 16px));
		padding: 6px;
		overflow: auto;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-3);
		transform-origin: top left;
	}
	.field-popup.up {
		transform-origin: bottom left;
	}
</style>
