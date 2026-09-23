<script module lang="ts">
	export interface MenuItem {
		label: string;
		onclick: () => void;
		danger?: boolean;
	}
</script>

<script lang="ts">
	import { EllipsisVertical } from '@lucide/svelte';
	import { scale } from '$lib/motion';

	let { items, label = 'Действия' }: { items: MenuItem[]; label?: string } = $props();
	let open = $state(false);
	let root: HTMLElement | undefined = $state();

	function onWindowClick(e: MouseEvent) {
		if (open && root && !root.contains(e.target as Node)) open = false;
	}
</script>

<svelte:window onclick={onWindowClick} onkeydown={(e) => e.key === 'Escape' && (open = false)} />

{#if items.length > 0}
	<div class="menu" bind:this={root}>
		<button
			class="trigger"
			aria-label={label}
			aria-haspopup="menu"
			aria-expanded={open}
			onclick={(e) => {
				e.preventDefault();
				e.stopPropagation();
				open = !open;
			}}
		>
			<EllipsisVertical size={18} />
		</button>
		{#if open}
			<div class="popup" role="menu" transition:scale={{ duration: 140, start: 0.94 }}>
				{#each items as item (item.label)}
					<button
						role="menuitem"
						class:danger={item.danger}
						onclick={(e) => {
							e.preventDefault();
							e.stopPropagation();
							open = false;
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
	.trigger:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.popup {
		position: absolute;
		z-index: 30;
		right: 0;
		top: calc(100% + 4px);
		min-width: 200px;
		padding: 4px;
		background: var(--surface);
		border-radius: var(--r);
		box-shadow: var(--shadow-3);
		transform-origin: top right;
	}
	.popup button {
		display: block;
		width: 100%;
		padding: 8px 12px;
		border: 0;
		border-radius: 10px;
		background: transparent;
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
