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
	/* Чипы-пилюли: активный — чернильный, остальные — светлые (как «Asia / Europe») */
	.tabs {
		display: flex;
		gap: 8px;
		margin: 0 calc(-1 * var(--s4)) var(--s4);
		padding: 2px var(--s4);
		overflow-x: auto;
		scrollbar-width: none;
	}
	.tabs::-webkit-scrollbar {
		display: none;
	}
	.tab {
		position: relative;
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		height: 38px;
		padding: 0 18px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text-2);
		font-size: 14.5px;
		font-weight: 550;
		text-decoration: none;
		transition:
			background-color var(--dur) var(--ease),
			border-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.tab:hover {
		color: var(--text);
		text-decoration: none;
	}
	.tab.active {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
	.count {
		font-size: 12px;
		color: var(--amber);
		font-weight: 650;
	}
	.tab.active .count {
		color: inherit;
		opacity: 0.7;
	}
	@media (min-width: 900px) {
		.tabs {
			margin: 0 0 var(--s4);
			padding: 2px 0;
		}
	}
</style>
