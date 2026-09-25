<script module lang="ts">
	export interface MenuItem {
		label: string;
		onclick: () => void;
		danger?: boolean;
	}
</script>

<script lang="ts">
	import { tick } from 'svelte';
	import { EllipsisVertical } from '@lucide/svelte';
	import { scale } from '$lib/motion';
	import { placeMenu } from './menuPlace';

	// Меню открывается в верхнем слое страницы (popover): его не обрезают карточки со скруглёнными
	// углами и прокруткой, а у нижнего края экрана оно раскрывается вверх.
	let { items, label = 'Действия' }: { items: MenuItem[]; label?: string } = $props();
	let open = $state(false);
	let root: HTMLElement | undefined = $state();
	let trigger: HTMLButtonElement | undefined = $state();
	let popup: HTMLDivElement | undefined = $state();
	let pos = $state({ top: 0, left: 0, up: false });
	// Пока место не посчитано, меню невидимо — иначе на кадр мелькнуло бы в углу экрана.
	let placed = $state(false);

	async function show() {
		placed = false;
		open = true;
		await tick();
		if (!popup || !trigger) return;
		try {
			popup.showPopover?.();
		} catch {
			/* без Popover API меню остаётся position: fixed */
		}
		// Размер без учёта анимации появления (она уменьшает меню на время показа).
		pos = placeMenu(
			trigger.getBoundingClientRect(),
			{ width: popup.offsetWidth, height: popup.offsetHeight },
			{ width: innerWidth, height: innerHeight }
		);
		placed = true;
		popup.querySelector<HTMLButtonElement>('button')?.focus({ preventScroll: true });
	}

	function hide() {
		if (!open) return;
		open = false;
	}

	/**
	 * Прокрутили страницу или список — меню едет за кнопкой; ушла кнопка с экрана — закрываем.
	 * Закрывать при любой прокрутке нельзя: плавная прокрутка и инерция на телефоне закрыли бы
	 * меню прямо под пальцем.
	 */
	function follow() {
		if (!open || !trigger || !popup) return;
		const t = trigger.getBoundingClientRect();
		if (t.bottom < 0 || t.top > innerHeight) return hide();
		pos = placeMenu(
			t,
			{ width: popup.offsetWidth, height: popup.offsetHeight },
			{ width: innerWidth, height: innerHeight }
		);
	}

	function onWindowClick(e: MouseEvent) {
		if (open && root && !root.contains(e.target as Node) && !popup?.contains(e.target as Node))
			hide();
	}
</script>

<svelte:window
	onclick={onWindowClick}
	onkeydown={(e) => {
		if (e.key === 'Escape' && open) {
			hide();
			trigger?.focus();
		}
	}}
	onresize={follow}
	onscrollcapture={(e) => {
		if (!popup?.contains(e.target as Node)) follow();
	}}
/>

{#if items.length > 0}
	<div class="menu" bind:this={root}>
		<button
			class="trigger"
			bind:this={trigger}
			aria-label={label}
			aria-haspopup="menu"
			aria-expanded={open}
			onclick={(e) => {
				e.preventDefault();
				e.stopPropagation();
				if (open) hide();
				else show();
			}}
		>
			<EllipsisVertical size={18} />
		</button>
		{#if open}
			<div
				class="popup"
				class:up={pos.up}
				role="menu"
				popover="manual"
				bind:this={popup}
				style:top="{pos.top}px"
				style:left="{pos.left}px"
				style:visibility={placed ? 'visible' : 'hidden'}
				transition:scale={{ duration: 140, start: 0.94 }}
			>
				{#each items as item (item.label)}
					<button
						role="menuitem"
						class:danger={item.danger}
						onclick={(e) => {
							e.preventDefault();
							e.stopPropagation();
							hide();
							item.onclick();
						}}>{item.label}</button
					>
				{/each}
			</div>
		{/if}
	</div>
{/if}

<style>
	.menu {
		position: relative;
		flex: none;
	}
	.trigger {
		display: grid;
		place-items: center;
		width: 32px;
		height: 32px;
		border: 0;
		border-radius: 50%;
		background: transparent;
		color: var(--text-3);
	}
	.trigger:hover,
	.trigger[aria-expanded='true'] {
		background: var(--surface-2);
		color: var(--text);
	}
	.popup {
		/* Сброс оформления popover по умолчанию: своё место и вид. */
		position: fixed;
		inset: auto;
		margin: 0;
		z-index: 1000;
		min-width: 200px;
		max-width: calc(100vw - 16px);
		padding: 4px;
		overflow: visible;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-3);
		transform-origin: top right;
	}
	.popup.up {
		transform-origin: bottom right;
	}
	.popup button {
		display: block;
		width: 100%;
		padding: 10px 12px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: inherit;
		text-align: left;
		font-size: 14px;
	}
	.popup button:hover,
	.popup button:focus-visible {
		background: var(--surface-2);
	}
	.popup .danger {
		color: var(--danger);
	}
</style>
