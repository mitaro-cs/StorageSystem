<script module lang="ts">
	export interface Tab {
		label: string;
		href?: string;
		value?: string;
		count?: number;
	}
</script>

<script lang="ts">
	import { page } from '$app/state';

	interface Props {
		tabs: Tab[];
		value?: string;
		onchange?: (v: string) => void;
		label?: string;
	}

	let { tabs, value, onchange, label = 'Разделы' }: Props = $props();

	function active(t: Tab): boolean {
		if (t.href) return page.url.pathname === t.href.split('?')[0];
		return t.value === value;
	}
</script>

<nav class="tabs" aria-label={label}>
	{#each tabs as t (t.label)}
		{#if t.href}
			<a
				href={t.href}
				class="tab"
				class:active={active(t)}
				aria-current={active(t) ? 'page' : undefined}
				>{t.label}{#if t.count}<span class="count num">{t.count}</span>{/if}</a
			>
		{:else}
			<button
				class="tab"
				class:active={active(t)}
				aria-pressed={active(t)}
				onclick={() => onchange?.(t.value ?? '')}
				>{t.label}{#if t.count}<span class="count num">{t.count}</span>{/if}</button
			>
		{/if}
	{/each}
</nav>

<style>
	.tabs {
		display: flex;
		gap: 4px;
		padding: 4px;
		background: var(--surface-2);
		border-radius: var(--r);
		overflow-x: auto;
		scrollbar-width: none;
		margin-bottom: var(--s4);
	}
	.tab {
		position: relative;
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		height: 32px;
		padding: 0 14px;
		border: 0;
		border-radius: 9px;
		background: transparent;
		color: var(--text-2);
		font-size: 14px;
		font-weight: 550;
		text-decoration: none;
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease),
			box-shadow var(--dur) var(--ease);
	}
	.tab:hover {
		color: var(--text);
		text-decoration: none;
	}
	.tab.active {
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-1);
	}
	.count {
		font-size: 12px;
		color: var(--amber);
		font-weight: 650;
	}
</style>
