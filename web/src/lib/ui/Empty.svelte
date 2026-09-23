<script lang="ts">
	import type { Snippet } from 'svelte';

	let { title, text, children }: { title: string; text?: string; children?: Snippet } = $props();
</script>

<div class="empty">
	<svg viewBox="0 0 96 64" aria-hidden="true">
		<rect x="14" y="10" width="68" height="44" rx="8" class="sheet" />
		<path d="M24 24h32M24 32h48M24 40h24" class="lines" />
		<circle cx="74" cy="14" r="8" class="dot" />
	</svg>
	<h3>{title}</h3>
	{#if text}<p class="muted">{text}</p>{/if}
	{#if children}<div class="actions">{@render children()}</div>{/if}
</div>

<style>
	.empty {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		gap: 8px;
		padding: var(--s7) var(--s4);
	}
	svg {
		width: 96px;
		margin-bottom: 8px;
	}
	.sheet {
		fill: var(--surface);
		stroke: var(--border-strong);
		stroke-width: 1.5;
	}
	.lines {
		stroke: var(--border-strong);
		stroke-width: 3;
		stroke-linecap: round;
	}
	.dot {
		fill: var(--amber-soft);
		stroke: var(--amber);
		stroke-width: 1.5;
	}
	p {
		max-width: 36ch;
	}
	.actions {
		margin-top: 8px;
	}
</style>
