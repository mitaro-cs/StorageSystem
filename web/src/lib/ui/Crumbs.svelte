<script lang="ts" module>
	// Путь до страницы: «Предметы › Физика › Материалы › Лекция 1».
	export interface Crumb {
		label: string;
		href?: string;
	}
</script>

<script lang="ts">
	import { ChevronRight } from '@lucide/svelte';

	let { items }: { items: Crumb[] } = $props();
</script>

<nav class="crumbs" aria-label="Путь">
	<ol>
		{#each items as c, i (i)}
			<li>
				{#if i > 0}<ChevronRight size={13} aria-hidden="true" />{/if}
				{#if c.href && i < items.length - 1}
					<a href={c.href}>{c.label}</a>
				{:else}
					<span aria-current={i === items.length - 1 ? 'page' : undefined}>{c.label}</span>
				{/if}
			</li>
		{/each}
	</ol>
</nav>

<style>
	.crumbs {
		margin-bottom: var(--s3);
		font-size: 13.5px;
		color: var(--text-3);
		min-width: 0;
	}
	ol {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 4px 6px;
		margin: 0;
		padding: 0;
		list-style: none;
	}
	li {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		min-width: 0;
		max-width: 100%;
	}
	a {
		color: var(--text-2);
		padding: 2px 0;
	}
	a:hover {
		color: var(--text);
	}
	span[aria-current] {
		color: var(--text);
		font-weight: 560;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
